package com.example.client.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-24：相机路径（录制/回放的唯一数据模型 + 严格编解码 + 采样器）。
 *
 * <p><b>为什么需要它。</b>本工程所有 A/B 都是"手动摆位 + 截图"，同一个问题换一次
 * 运行就复现不了：视角差 3 度、站位差 0.4 格，画面的差异就盖过要比较的那个改动。
 * 参考实现（NVIDIA rtx-remix-minecraft 的 {@code CameraPath}/{@code CameraPathCodec}）
 * 的做法是：把位姿录成时间序列，回放用固定步长采样，于是每个基准帧的相机完全一致，
 * 截图、帧时间、GPU 数据都可以逐帧对比。</p>
 *
 * <p><b>数据。</b>一条路径 = 一串航点 {@code (t, x, y, z, yaw, pitch)}，{@code t}
 * 是从 0 开始递增的秒数（录制时按墙钟增量累加，单步上限 {@link #MAX_STEP_SECONDS}，
 * 防止长卡顿把整段路径拉长）。{@code yaw} 存**展开后**的值（连续累加，不走 ±180
 * 折返），这样插值不会在正北附近突然转一圈 —— 展开在 {@link #unwrapYaw} 里做，
 * 录制端和读取端各做一次，保证手写 JSON 也不会出错。</p>
 *
 * <p><b>采样。</b>按时间做分段三次 Hermite（切向取相邻差分，端点单侧），即参考实现
 * 所说的 "time-aware shape-preserving cubic Hermite over nonuniform waypoint times"。
 * 只有两个航点时退化为线性；只有一个航点时恒定。所有运算都是 double 确定性运算，
 * 同样的 {@code t} 在任何一局都给出同一组数。</p>
 *
 * <p><b>编解码是严格的</b>（照参考实现 {@code CameraPathCodec} 的口径）：文件头必须是
 * {@code "kind":"orion-camera-path"}、{@code "version":1}；出现未知键、类型不符、
 * 非有限数、时间不严格递增、空航点表，一律抛 {@link InvalidCameraPathException}，
 * 而不是"尽量读进来"。理由是静默容错会让一次坏录制变成一次看起来正常、实际不可比的
 * 基准运行。</p>
 */
public final class CameraPath {

    /** 文件头标识。改结构必须同时改版本号，不回退兼容。 */
    public static final String KIND = "orion-camera-path";
    /** 当前版本。1 = 首个版本。 */
    public static final int VERSION = 1;

    /** 单步时间上限（秒）：长卡顿（编译 SPV、区块爆量）不应该把路径拉长。 */
    public static final double MAX_STEP_SECONDS = 0.25;
    /** 相邻采样点的最小间隔（秒），低于它丢弃：避免高帧率下航点表爆掉。 */
    public static final double MIN_STEP_SECONDS = 1.0 / 1000.0;

    private static final Set<String> ROOT_KEYS = Set.of(
            "kind", "version", "name", "fixedStepHz", "createdAt", "waypoints");
    private static final Set<String> WAYPOINT_KEYS = Set.of(
            "t", "x", "y", "z", "yaw", "pitch");

    /** 一个位姿。角度单位是度（与 Minecraft 的 {@code Entity#getYRot()} 一致）。 */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    /** 一个航点：时间（秒）+ 位姿。 */
    public record Waypoint(double t, Pose pose) {
    }

    /** 路径文件不合法。消息里带足够定位信息（键名/序号）。 */
    public static final class InvalidCameraPathException extends RuntimeException {
        public InvalidCameraPathException(final String message) {
            super(message);
        }
    }

    private final String name;
    private final int fixedStepHz;
    private final long createdAtEpochSeconds;
    private final List<Waypoint> waypoints;
    private final double duration;

    private CameraPath(final String name, final int fixedStepHz,
                       final long createdAtEpochSeconds, final List<Waypoint> waypoints) {
        if (waypoints.isEmpty()) {
            throw new InvalidCameraPathException("waypoints must not be empty");
        }
        this.name = name;
        this.fixedStepHz = fixedStepHz;
        this.createdAtEpochSeconds = createdAtEpochSeconds;
        this.waypoints = List.copyOf(waypoints);
        this.duration = waypoints.get(waypoints.size() - 1).t();
    }

    /**
     * 从录制结果构造。航点必须已按时间升序，且时间严格递增（录制端保证）。
     * {@code yaw} 在这里做一次展开，调用方传原始角度即可。
     */
    public static CameraPath of(final String name, final int fixedStepHz,
                                final long createdAtEpochSeconds,
                                final List<Waypoint> rawWaypoints) {
        if (rawWaypoints.isEmpty()) {
            throw new InvalidCameraPathException("waypoints must not be empty");
        }
        final List<Waypoint> cleaned = new ArrayList<>(rawWaypoints.size());
        double lastT = Double.NEGATIVE_INFINITY;
        float previousYaw = Float.NaN;
        for (int i = 0; i < rawWaypoints.size(); i++) {
            final Waypoint waypoint = rawWaypoints.get(i);
            if (!(waypoint.t() > lastT)) {
                throw new InvalidCameraPathException(
                        "waypoint[" + i + "].t must be strictly increasing ("
                                + lastT + " -> " + waypoint.t() + ")");
            }
            lastT = waypoint.t();
            final Pose pose = waypoint.pose();
            final float yaw = Float.isNaN(previousYaw)
                    ? pose.yaw()
                    : unwrapYaw(previousYaw, pose.yaw());
            previousYaw = yaw;
            cleaned.add(new Waypoint(waypoint.t(),
                    new Pose(pose.x(), pose.y(), pose.z(), yaw, pose.pitch())));
        }
        return new CameraPath(name, fixedStepHz, createdAtEpochSeconds, cleaned);
    }

    /** 把 {@code yaw} 调整到离 {@code previous} 最近的同余角（差 ≤ 180°）。 */
    public static float unwrapYaw(final float previous, final float yaw) {
        float adjusted = yaw;
        while (adjusted - previous > 180.0f) {
            adjusted -= 360.0f;
        }
        while (adjusted - previous < -180.0f) {
            adjusted += 360.0f;
        }
        return adjusted;
    }

    public String name() {
        return this.name;
    }

    public int fixedStepHz() {
        return this.fixedStepHz;
    }

    public long createdAtEpochSeconds() {
        return this.createdAtEpochSeconds;
    }

    public List<Waypoint> waypoints() {
        return Collections.unmodifiableList(this.waypoints);
    }

    public int size() {
        return this.waypoints.size();
    }

    /** 路径总时长（秒）。 */
    public double duration() {
        return this.duration;
    }

    /** 按时间采样；{@code t} 会被夹到 {@code [0, duration]}。 */
    public Pose sample(final double seconds) {
        if (this.waypoints.size() == 1) {
            return this.waypoints.get(0).pose();
        }
        final double t = Math.max(0.0, Math.min(this.duration, seconds));
        int index = 0;
        while (index + 2 < this.waypoints.size()
                && this.waypoints.get(index + 1).t() <= t) {
            index++;
        }
        final Waypoint p0 = this.waypoints.get(index);
        final Waypoint p1 = this.waypoints.get(index + 1);
        final double span = p1.t() - p0.t();
        if (span <= 0.0) {
            return p1.pose();
        }
        final double u = Math.max(0.0, Math.min(1.0, (t - p0.t()) / span));

        // 切向：内部点取相邻差分，端点取单侧斜率。
        final Waypoint prev = index > 0 ? this.waypoints.get(index - 1) : null;
        final Waypoint next = index + 2 < this.waypoints.size()
                ? this.waypoints.get(index + 2) : null;

        final double[] m0 = tangent(prev, p0, p1);
        final double[] m1 = tangent(p0, p1, next);

        final double u2 = u * u;
        final double u3 = u2 * u;
        final double h00 = 2.0 * u3 - 3.0 * u2 + 1.0;
        final double h10 = u3 - 2.0 * u2 + u;
        final double h01 = -2.0 * u3 + 3.0 * u2;
        final double h11 = u3 - u2;

        final double x = h00 * p0.pose().x() + h10 * span * m0[0]
                + h01 * p1.pose().x() + h11 * span * m1[0];
        final double y = h00 * p0.pose().y() + h10 * span * m0[1]
                + h01 * p1.pose().y() + h11 * span * m1[1];
        final double z = h00 * p0.pose().z() + h10 * span * m0[2]
                + h01 * p1.pose().z() + h11 * span * m1[2];
        final double yaw = h00 * p0.pose().yaw() + h10 * span * m0[3]
                + h01 * p1.pose().yaw() + h11 * span * m1[3];
        final double pitch = h00 * p0.pose().pitch() + h10 * span * m0[4]
                + h01 * p1.pose().pitch() + h11 * span * m1[4];

        return new Pose(x, y, z, (float) yaw, (float) pitch);
    }

    /** {@code (after - before) / (t_after - t_before)}；缺一侧时用另一侧的单侧斜率。 */
    private static double[] tangent(final Waypoint before, final Waypoint at,
                                    final Waypoint after) {
        if (before != null && after != null) {
            final double span = after.t() - before.t();
            if (span > 0.0) {
                return new double[]{
                        (after.pose().x() - before.pose().x()) / span,
                        (after.pose().y() - before.pose().y()) / span,
                        (after.pose().z() - before.pose().z()) / span,
                        (after.pose().yaw() - before.pose().yaw()) / span,
                        (after.pose().pitch() - before.pose().pitch()) / span};
            }
        }
        final Waypoint a = before != null ? before : at;
        final Waypoint b = after != null ? after : at;
        final double span = b.t() - a.t();
        if (span <= 0.0) {
            return new double[5];
        }
        return new double[]{
                (b.pose().x() - a.pose().x()) / span,
                (b.pose().y() - a.pose().y()) / span,
                (b.pose().z() - a.pose().z()) / span,
                (b.pose().yaw() - a.pose().yaw()) / span,
                (b.pose().pitch() - a.pose().pitch()) / span};
    }

    // ------------------------------------------------------------------
    // 编解码（严格）
    // ------------------------------------------------------------------

    public String toJson() {
        final JsonObject root = new JsonObject();
        root.addProperty("kind", KIND);
        root.addProperty("version", VERSION);
        root.addProperty("name", this.name);
        root.addProperty("fixedStepHz", this.fixedStepHz);
        root.addProperty("createdAt", this.createdAtEpochSeconds);
        final JsonArray array = new JsonArray();
        for (final Waypoint waypoint : this.waypoints) {
            final JsonObject item = new JsonObject();
            item.addProperty("t", round(waypoint.t()));
            item.addProperty("x", round(waypoint.pose().x()));
            item.addProperty("y", round(waypoint.pose().y()));
            item.addProperty("z", round(waypoint.pose().z()));
            item.addProperty("yaw", round(waypoint.pose().yaw()));
            item.addProperty("pitch", round(waypoint.pose().pitch()));
            array.add(item);
        }
        root.add("waypoints", array);
        return GSON.toJson(root);
    }

    /** 6 位小数足够（1 格 = 1 米，6 位是微米级），并且让 diff 可读。 */
    private static double round(final double value) {
        return Math.round(value * 1.0e6) / 1.0e6;
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static CameraPath fromJson(final String json) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (final JsonParseException failure) {
            throw new InvalidCameraPathException("not valid JSON: " + failure.getMessage());
        }
        if (!parsed.isJsonObject()) {
            throw new InvalidCameraPathException("root must be a JSON object");
        }
        final JsonObject root = parsed.getAsJsonObject();
        rejectUnknownKeys(root, ROOT_KEYS, "root");

        final String kind = requireString(root, "kind", "root");
        if (!KIND.equals(kind)) {
            throw new InvalidCameraPathException(
                    "root.kind must be \"" + KIND + "\", got \"" + kind + "\"");
        }
        final int version = requireInt(root, "version", "root");
        if (version != VERSION) {
            throw new InvalidCameraPathException(
                    "unsupported root.version " + version + " (expected " + VERSION + ")");
        }
        final String name = requireString(root, "name", "root");
        final int fixedStepHz = root.has("fixedStepHz")
                ? requireInt(root, "fixedStepHz", "root")
                : 60;
        if (fixedStepHz <= 0 || fixedStepHz > 1000) {
            throw new InvalidCameraPathException(
                    "root.fixedStepHz out of range: " + fixedStepHz);
        }
        final long createdAt = root.has("createdAt")
                ? (long) requireDouble(root, "createdAt", "root")
                : 0L;

        final JsonElement rawWaypoints = root.get("waypoints");
        if (rawWaypoints == null || !rawWaypoints.isJsonArray()) {
            throw new InvalidCameraPathException("root.waypoints must be an array");
        }
        final JsonArray array = rawWaypoints.getAsJsonArray();
        if (array.isEmpty()) {
            throw new InvalidCameraPathException("root.waypoints must not be empty");
        }
        final List<Waypoint> read = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            final JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new InvalidCameraPathException("waypoint[" + i + "] must be an object");
            }
            final JsonObject item = element.getAsJsonObject();
            rejectUnknownKeys(item, WAYPOINT_KEYS, "waypoint[" + i + "]");
            read.add(new Waypoint(
                    requireDouble(item, "t", "waypoint[" + i + "]"),
                    new Pose(
                            requireDouble(item, "x", "waypoint[" + i + "]"),
                            requireDouble(item, "y", "waypoint[" + i + "]"),
                            requireDouble(item, "z", "waypoint[" + i + "]"),
                            (float) requireDouble(item, "yaw", "waypoint[" + i + "]"),
                            (float) requireDouble(item, "pitch", "waypoint[" + i + "]"))));
        }
        // of() 会再校验时间严格递增并展开 yaw。
        return of(name, fixedStepHz, createdAt, read);
    }

    private static void rejectUnknownKeys(final JsonObject object, final Set<String> allowed,
                                          final String where) {
        for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new InvalidCameraPathException(
                        where + ": unknown key \"" + entry.getKey() + "\"");
            }
        }
    }

    private static String requireString(final JsonObject object, final String key,
                                        final String where) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !((JsonPrimitive) element).isString()) {
            throw new InvalidCameraPathException(where + "." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static int requireInt(final JsonObject object, final String key,
                                  final String where) {
        final double value = requireDouble(object, key, where);
        if (value != Math.rint(value)) {
            throw new InvalidCameraPathException(where + "." + key + " must be an integer");
        }
        return (int) value;
    }

    private static double requireDouble(final JsonObject object, final String key,
                                        final String where) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !((JsonPrimitive) element).isNumber()) {
            throw new InvalidCameraPathException(where + "." + key + " must be a number");
        }
        final double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new InvalidCameraPathException(where + "." + key + " must be finite");
        }
        return value;
    }
}
