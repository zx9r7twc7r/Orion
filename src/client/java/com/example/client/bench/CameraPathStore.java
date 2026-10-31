package com.example.client.bench;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2026-10-24：相机路径的落盘位置与读写。
 *
 * <p>目录：{@code <gameDir>/config/orion/camera-paths/<world-key>/<name>.json}。</p>
 *
 * <p>{@code world-key} 默认是"维度 id + 世界名"：同一个 {@code shot-1} 在主世界和下界
 * 是两条路径，单人存档与服务器也互不覆盖 —— 否则一次误回放会把坐标带到一个完全不相干的
 * 世界里（回放只替换相机，不做碰撞，玩家本体还在原地，画面会直接穿地）。</p>
 *
 * <p>用 {@code -Dorion.campath.world=<tag>} 可以覆盖 {@code world-key} 的第二段，
 * 便于把基准固定成"服务器 A 的出生点"这种与本地存档名无关的标签。</p>
 */
public final class CameraPathStore {

    private static final String HEADER = "orion camera path v1. 手改也可以，但编解码是严格的：\n"
            + "未知键 / 类型不符 / 时间不递增 一律拒绝整份文件。\n"
            + "字段：kind=orion-camera-path, version=1, name, fixedStepHz, createdAt,\n"
            + "waypoints[] = { t(秒,递增), x, y, z, yaw(度, 已展开), pitch(度) }\n";

    private CameraPathStore() {
    }

    /** 当前世界键；世界里还没有 level 时返回 {@code null}。 */
    public static String worldKey(final Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        final Identifier dimension = minecraft.level.dimension().identifier();
        final String dimensionPart = sanitize(dimension.toString());
        final String override = System.getProperty("orion.campath.world");
        if (override != null && !override.isBlank()) {
            return dimensionPart + "__" + sanitize(override);
        }
        return dimensionPart + "__" + sanitize(worldLabel(minecraft));
    }

    /** 单人存档目录名 / 服务器地址 / {@code "unknown"}。 */
    private static String worldLabel(final Minecraft minecraft) {
        try {
            if (minecraft.hasSingleplayerServer()
                    && minecraft.getSingleplayerServer() != null) {
                final String name = minecraft.getSingleplayerServer()
                        .getWorldData().getLevelName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            final ServerData server = minecraft.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isBlank()) {
                return server.ip;
            }
        } catch (final Throwable ignored) {
        }
        return "unknown";
    }

    /** 路径目录（不保证存在）。{@code worldKey} 为 {@code null} 时返回 {@code null}。 */
    public static Path directory(final Minecraft minecraft, final String worldKey) {
        if (minecraft == null || minecraft.gameDirectory == null || worldKey == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath()
                .resolve("config").resolve("orion").resolve("camera-paths")
                .resolve(worldKey);
    }

    public static Path file(final Minecraft minecraft, final String worldKey,
                            final String name) {
        final Path directory = directory(minecraft, worldKey);
        return directory == null ? null : directory.resolve(sanitize(name) + ".json");
    }

    public static void save(final Path file, final CameraPath path) throws IOException {
        if (file == null) {
            throw new IOException("no camera-path file resolved (not in a world?)");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, path.toJson(), StandardCharsets.UTF_8);
    }

    public static CameraPath load(final Path file) throws IOException {
        if (file == null) {
            throw new IOException("no camera-path file resolved (not in a world?)");
        }
        return CameraPath.fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static List<String> list(final Minecraft minecraft, final String worldKey) {
        final Path directory = directory(minecraft, worldKey);
        final List<String> names = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (final Path item : stream) {
                final String fileName = item.getFileName().toString();
                names.add(fileName.substring(0, fileName.length() - ".json".length()));
            }
        } catch (final IOException ignored) {
            return names;
        }
        Collections.sort(names);
        return names;
    }

    /** 目录里的使用说明（第一次写路径时落一份，避免有人猜格式）。 */
    public static void ensureReadme(final Minecraft minecraft, final String worldKey) {
        final Path directory = directory(minecraft, worldKey);
        if (directory == null) {
            return;
        }
        final Path readme = directory.resolve("README.txt");
        if (Files.isRegularFile(readme)) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(readme, HEADER, StandardCharsets.UTF_8);
        } catch (final IOException ignored) {
        }
    }

    /** 只允许 [a-z0-9._-]；其余换成 {@code _}，并限长，避免路径穿越与超长名。 */
    public static String sanitize(final String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        final StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && builder.length() < 96; i++) {
            final char c = Character.toLowerCase(raw.charAt(i));
            final boolean safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            builder.append(safe ? c : '_');
        }
        return builder.length() == 0 ? "unknown" : builder.toString();
    }

    /** 给日志用：相对 gameDir 的短路径（不泄漏用户目录）。 */
    public static String describe(final Minecraft minecraft, final Path file) {
        if (file == null) {
            return "<none>";
        }
        if (minecraft != null && minecraft.gameDirectory != null) {
            final Path root = minecraft.gameDirectory.toPath();
            if (file.startsWith(root)) {
                return root.relativize(file).toString().replace('\\', '/');
            }
        }
        return file.getFileName().toString();
    }
}
