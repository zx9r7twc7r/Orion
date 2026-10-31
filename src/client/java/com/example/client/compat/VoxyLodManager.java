package com.example.client.compat;

import com.example.vulkan.NativeVulkan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maintains the native far-terrain proxy from Voxy's immutable LOD snapshots. */
public final class VoxyLodManager {
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int HANDOFF_MARGIN_BLOCKS = 64;
    private static final int MAX_LOD_BYTES_PER_MESH = 64 * 1024 * 1024;
    private static final int MAX_UPLOADS_PER_FRAME = 2;
    private static final boolean LOG_UPLOADS = false;
    

    private static final boolean DIAG = !"false".equalsIgnoreCase(System.getProperty("orion.lod.diag", "true"));

    private static final boolean PERF = !"false".equalsIgnoreCase(System.getProperty("orion.lod.perf", "true"));
    private static final String AB_MARKER = "orion_lod_off";
    private static final String AB_AUTO_MARKER = "orion_lod_ab";
    private static final int PERF_WINDOW_FRAMES = 300;
    private static final int AB_CHECK_FRAMES = 30;
    private static final long[] PERF_DELTAS = new long[PERF_WINDOW_FRAMES];
    private static long perfLastNanos;
    private static int perfCount;
    private static long perfUploadBytes;
    private static long perfUploadMeshes;
    private static int perfMarkerTicks;
    private static boolean farFieldSuppressed;

    public static final int PERF_BRIDGE = 0;
    public static final int PERF_LOD = 1;
    public static final int PERF_ATLAS = 2;
    public static final int PERF_DH = 3;
    public static final int PERF_FFG = 4;
    public static final int PERF_CAPTURE = 5;
    private static final String[] PERF_PART_NAMES = {"bridge", "lod", "atlas", "dh", "ffg", "capture"};
    private static final long[] PERF_PART_NANOS = new long[PERF_PART_NAMES.length];
    /** 组件耗时累加。调用点在渲染线程热路径上，所以这里必须零分配。 */
    public static void perfAdd(int part, long nanos) {
        if (!PERF) return;
        PERF_PART_NANOS[part] += nanos;
    }

    private static final Map<Long, Uploaded> UPLOADED_VERSIONS = new HashMap<>();

    /**
     * 一个已上传网格的记账：版本 + 位置/宽度 + 两个桶的 quad 数（+ 透明桶里的水面 quad 数）。
     *
     * <p>{@code version} 参与"是否需要重传"的判定；其余字段只服务于 {@link #reportSeam}
     * 的环统计——即"这一环到底交给了原生侧多少几何、其中有多少是水"。
     * 没有它就只能知道"覆盖表说这里有网格"，永远分不清"上传过但原生侧没画"和
     * "压根没上传"，也分不清"远处没水"是没几何还是没水几何。</p>
     */
    private record Uploaded(long version, int originX, int originZ, int width,
                            int opaqueQuads, int transparentQuads, int waterQuads) {
    }

    /** 透明桶里的水面 quad 数（材质字节 12，位置与 voxy 桥的 writeVertex 一致）。 */
    private static int countWaterQuads(byte[] transparent) {
        int count = 0;
        for (int quad = 0; quad + 64 <= transparent.length; quad += 64) {
            if ((transparent[quad + 14] & 0xFF) == 12) count++;
        }
        return count;
    }

    private static final Map<Long, VoxyCompat.LodMesh> PENDING_UPLOADS = new LinkedHashMap<>();
    private static Object world;
    private static long observedRevision = Long.MIN_VALUE;
    

    private static long observedWindowX = Long.MIN_VALUE;
    private static long observedWindowZ = Long.MIN_VALUE;
    

    private static long perfRetractWindow;
    private static long perfRetractGone;
    private static long diagnosticTicks;
    private static boolean lodProviding;
    private static int uploadedAtlasHash;
    

    private static volatile Set<Long> coveredLodCells = Set.of();
    private static List<VoxyCompat.LodMesh> coverageCandidates = List.of();

    private VoxyLodManager() {
    }

    /** 某 16 格 section 是否真的落在非空 LOD 网格的覆盖范围内（64 格 cell 粒度）。 */
    public static boolean coversSection(int sectionX, int sectionZ) {
        return coveredLodCells.contains(cellKey(
                Math.floorDiv(sectionX, FarFieldGate.LOD_SECTION_BLOCKS / BLOCKS_PER_CHUNK),
                Math.floorDiv(sectionZ, FarFieldGate.LOD_SECTION_BLOCKS / BLOCKS_PER_CHUNK)));
    }

    /** 把一个 mesh 的 XZ 矩形摊到 64 格 cell 网格上（origin 未必对齐，故用区间而非长度）。 */
    private static void markCoveredCells(Set<Long> out, VoxyCompat.LodMesh mesh) {
        final int cellBlocks = FarFieldGate.LOD_SECTION_BLOCKS;
        final int firstX = Math.floorDiv(mesh.originX(), cellBlocks);
        final int lastX = Math.floorDiv(mesh.originX() + mesh.width() - 1, cellBlocks);
        final int firstZ = Math.floorDiv(mesh.originZ(), cellBlocks);
        final int lastZ = Math.floorDiv(mesh.originZ() + mesh.width() - 1, cellBlocks);
        for (int cellX = firstX; cellX <= lastX; cellX++) {
            for (int cellZ = firstZ; cellZ <= lastZ; cellZ++) {
                out.add(cellKey(cellX, cellZ));
            }
        }
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    /**
     * 重算可接管的 64 格 cell 覆盖表。
     *
     * <p>判据是「这个 key <b>曾经</b>上传成功过」（{@code UPLOADED_VERSIONS} 含键），
     * <b>不是</b>「当前版本已经上传」。这个差别很关键：voxy 的 revision 大约每秒变
     * 两三次，每次都把改动过的网格重新排队，而上限只有每帧 {@link #MAX_UPLOADS_PER_FRAME}
     * 个 ⇒ 若按版本相等判定，这些 cell 的覆盖会在「版本变了」到「新版本传完」之间
     * <b>反复消失</b>。覆盖一消失，FarFieldGate 就把那片近场 section 重新标脏重编译，
     * 随后覆盖回来又把它们零顶点擦掉 ⇒ 远场一圈 section 反复重编译 + 反复上传
     * （可见的远近闪烁，且白扔大量原版编译）。而原生侧在收到新版本之前一直持有旧版本
     * 几何（{@code uploadVoxyLodMesh} 是整体替换），所以「曾经上传过」期间那片位置
     * <b>始终有几何</b>，覆盖不该掉。
     *
     * <p>调用点两处：revision 变化（候选快照换了）与每帧实际发生了上传之后 —— 后者
     * 让新网格在首帧上传完成时立刻计入覆盖。</p>
     */
    private static void refreshCoverage() {
        if (coverageCandidates.isEmpty()) {
            coveredLodCells = Set.of();
            return;
        }
        HashSet<Long> covered = new HashSet<>();
        for (VoxyCompat.LodMesh mesh : coverageCandidates) {
            if (!UPLOADED_VERSIONS.containsKey(mesh.key())) continue;
            markCoveredCells(covered, mesh);
        }
        coveredLodCells = covered.isEmpty() ? Set.of() : Set.copyOf(covered);
    }

    private static int diagRuns;

    private static void diagnoseMesh(VoxyCompat.LodMesh mesh) {
        if (diagRuns >= 4) return;
        try {
            byte[] transparent = mesh.transparent();
            byte[] opaque = mesh.opaque();
            if (transparent.length == 0) return;
            diagRuns++;
            StringBuilder line = new StringBuilder("[VOXY-LOD DIAG] key=").append(mesh.key())
                    .append(" width=").append(mesh.width())
                    .append(" opaqueQuads=").append(opaque.length / 64)
                    .append(" opaqueMat=").append(materialHistogram(opaque))
                    .append(" transparentQuads=").append(transparent.length / 64)
                    .append(" transparentMat=").append(materialHistogram(transparent));
            int leaves = 0;
            int noTile = 0;
            int zeroShade = 0;
            int firstTile = -1;
            int waterTile = 0;
            int waterNoTile = 0;
            for (int quad = 0; quad + 64 <= transparent.length; quad += 64) {
                int material = transparent[quad + 14] & 0xFF;
                if (material != 1 && material != 12) continue;
                int u = (transparent[quad + 6] & 0xFF) | ((transparent[quad + 7] & 0xFF) << 8);
                int v = (transparent[quad + 8] & 0xFF) | ((transparent[quad + 9] & 0xFF) << 8);
                boolean tiled = u != 0xFFFF;
                if (material == 12) {
                    if (tiled) {
                        waterTile++;
                    } else {
                        waterNoTile++;
                    }
                    continue;
                }
                leaves++;
                if (!tiled) {
                    noTile++;
                    continue;
                }
                if ((transparent[quad + 15] & 0xFF) == 0) zeroShade++;
                if (firstTile < 0) firstTile = (u / 16) + (v / 16) * 64;
            }
            line.append(" leaves=").append(leaves)
                    .append(" leavesNoTile=").append(noTile)
                    .append(" leavesZeroShade=").append(zeroShade)
                    .append(" waterTile=").append(waterTile)
                    .append(" waterNoTile=").append(waterNoTile);
            if (firstTile >= 0) {
                line.append(" leafTile=").append(firstTile).append(' ').append(tileAlphaStats(firstTile));
            }
            System.err.println(line);
        } catch (Throwable failure) {
            System.err.println("[VOXY-LOD DIAG] failed: " + failure);
        }
    }

    

    private static long ringReportTicks;

    private static void reportRings() {
        if ((++ringReportTicks % 600L) != 0L) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        var camera = VoxyCompat.cameraPosition(minecraft);
        if (camera == null) return;
        int[] rings = new int[8];
        int nearest = Integer.MAX_VALUE;
        int farthest = 0;
        for (long key : coveredLodCells) {
            int cellX = (int) (key >> 32);
            int cellZ = (int) key;
            double dx = cellX * 64.0 + 32.0 - camera.x;
            double dz = cellZ * 64.0 + 32.0 - camera.z;
            int blocks = (int) Math.sqrt(dx * dx + dz * dz);
            if (blocks < nearest) nearest = blocks;
            if (blocks > farthest) farthest = blocks;
            rings[Math.min(blocks / 128, rings.length - 1)]++;
        }
        StringBuilder line = new StringBuilder("[VOXY-LOD RINGS] cells=").append(coveredLodCells.size());
        for (int ring = 0; ring < rings.length; ring++) {
            line.append(" r").append(ring * 128).append('-')
                    .append(ring == rings.length - 1 ? "+" : String.valueOf(ring * 128 + 127))
                    .append('=').append(rings[ring]);
        }
        line.append(" nearest=").append(nearest == Integer.MAX_VALUE ? -1 : nearest)
                .append(" farthest=").append(farthest)
                .append(" vanilla=").append(FarFieldWindow.renderDistanceChunks() * 16)
                .append(" seam=").append(FarFieldGate.seamSections() * 16);
        int[] levelNear = new int[4];
        int[] levelFar = new int[4];
        int[] levelCount = new int[4];
        java.util.Arrays.fill(levelNear, Integer.MAX_VALUE);
        for (VoxyCompat.LodMesh mesh : VoxyCompat.meshes()) {
            if (!valid(mesh)) continue;
            int level = Integer.numberOfTrailingZeros(mesh.width()) - 5;
            if (level < 0 || level >= 4) continue;
            double dx = mesh.originX() + mesh.width() * 0.5 - camera.x;
            double dz = mesh.originZ() + mesh.width() * 0.5 - camera.z;
            int blocks = (int) Math.sqrt(dx * dx + dz * dz);
            levelCount[level]++;
            if (blocks < levelNear[level]) levelNear[level] = blocks;
            if (blocks > levelFar[level]) levelFar[level] = blocks;
        }
        for (int level = 0; level < 4; level++) {
            if (levelCount[level] == 0) continue;
            line.append(" | L").append(level)
                    .append(" n=").append(levelCount[level])
                    .append(" dist=").append(levelNear[level]).append('-').append(levelFar[level]);
        }
        System.err.println(line);
    }

    private static long seamReportTicks;

    private static void reportSeam() {
        long ticks = ++seamReportTicks;
        boolean wantMap = (ticks % 1200L) == 0L;
        if ((ticks % 600L) != 0L) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        var camera = VoxyCompat.cameraPosition(minecraft);
        if (camera == null) return;

        int[] published = new int[RING_COUNT];
        int[] dropped = new int[RING_COUNT];
        for (VoxyCompat.LodMesh mesh : VoxyCompat.meshes()) {
            if (!valid(mesh)) continue;
            if (mesh.opaque().length == 0 && mesh.transparent().length == 0) continue;
            int ring = ringOf(mesh.originX() + mesh.width() * 0.5,
                    mesh.originZ() + mesh.width() * 0.5, camera.x, camera.z);
            if (FarFieldGate.lodWindowOpen() && overlapsVanillaWindow(mesh, camera.x, camera.z)) {
                dropped[ring]++;
            } else {
                published[ring]++;
            }
        }
        int[] uploaded = new int[RING_COUNT];
        int[] opaqueQuads = new int[RING_COUNT];
        int[] alphaQuads = new int[RING_COUNT];
        int[] waterQuads = new int[RING_COUNT];
        for (Uploaded entry : UPLOADED_VERSIONS.values()) {
            int ring = ringOf(entry.originX() + entry.width() * 0.5,
                    entry.originZ() + entry.width() * 0.5, camera.x, camera.z);
            uploaded[ring]++;
            opaqueQuads[ring] += entry.opaqueQuads();
            alphaQuads[ring] += entry.transparentQuads();
            waterQuads[ring] += entry.waterQuads();
        }
        int[] covered = new int[RING_COUNT];
        for (long key : coveredLodCells) {
            covered[ringOf((int) (key >> 32) * 64.0 + 32.0,
                    (int) key * 64.0 + 32.0, camera.x, camera.z)]++;
        }

        HashSet<Long> uploadedSections = new HashSet<>();
        HashSet<Long> suppressedSections = new HashSet<>();
        FarFieldGate.collectStates(uploadedSections, suppressedSections);
        int[] vanilla = new int[RING_COUNT];
        for (long key : uploadedSections) {
            SectionPos pos = SectionPos.of(key);
            vanilla[ringOf(pos.x() * 16.0 + 8.0, pos.z() * 16.0 + 8.0, camera.x, camera.z)]++;
        }
        int[] erased = new int[RING_COUNT];
        for (long key : suppressedSections) {
            SectionPos pos = SectionPos.of(key);
            erased[ringOf(pos.x() * 16.0 + 8.0, pos.z() * 16.0 + 8.0, camera.x, camera.z)]++;
        }

        StringBuilder line = new StringBuilder(
                "[VOXY-LOD SEAM] 切比雪夫128格环 pub/drop/up/opqK/alpK/wtrK/cov/sup/van");
        for (int ring = 0; ring < RING_COUNT; ring++) {
            line.append(" r").append(ring * 128).append('-')
                    .append(ring == RING_COUNT - 1 ? "+" : String.valueOf(ring * 128 + 127))
                    .append('=').append(published[ring]).append('/').append(dropped[ring])
                    .append('/').append(uploaded[ring]).append('/').append(opaqueQuads[ring] >> 10)
                    .append('/').append(alphaQuads[ring] >> 10).append('/').append(waterQuads[ring] >> 10)
                    .append('/').append(covered[ring])
                    .append('/').append(erased[ring]).append('/').append(vanilla[ring]);
        }
        System.err.println(line);
        if (wantMap) printSeamMap(camera.x, camera.z, suppressedSections);
    }

    private static final int RING_COUNT = 8;

    /** 128 格切比雪夫环号（0 = 相机所在方环），夹到最高环。 */
    private static int ringOf(double blockX, double blockZ, double cameraX, double cameraZ) {
        double distance = Math.max(Math.abs(blockX - cameraX), Math.abs(blockZ - cameraZ));
        return Math.min((int) (distance / 128.0), RING_COUNT - 1);
    }

    /**
     * 玩家朝向（度 + 罗盘字母），只为把地图和用户看到的画面方向对上：
     * MC 的 yaw 0 = +Z(南)、90 = -X(西)、180 = -Z(北)、270 = +X(东)。
     */
    private static String facing(Minecraft minecraft) {
        if (minecraft.player == null) return "?";
        int yaw = (int) Math.floorMod((long) minecraft.player.getYRot(), 360L);
        String direction;
        if (yaw < 45 || yaw >= 315) {
            direction = "S(+Z)";
        } else if (yaw < 135) {
            direction = "W(-X)";
        } else if (yaw < 225) {
            direction = "N(-Z)";
        } else {
            direction = "E(+X)";
        }
        return yaw + direction;
    }

    /**
     * 每 20 秒一张 ASCII 图：相机周围 ±8 个 <b>64 格 cell</b>（±512 格，足够一次跨过
     * seam=128、L0 外沿 224、L1 内沿 224 三个边界）。上行是北（-Z），左列是西（-X）。
     *
     * <p>每格字符（优先级从高到低）：
     * <ul>
     *   <li>{@code #} 该 cell 有已上传的 LOD 几何 ⇒ 远场在这一格有东西可画；</li>
     *   <li>{@code S} 该 cell <b>有原版 section 被擦除，却没有任何已上传的 LOD 几何</b>
     *       ⇒ 这就是用户看到的真空；</li>
     *   <li>{@code p} 有已发布的 LOD 几何但没上传（积压 / 被 valid 挡下）；</li>
     *   <li>{@code x} 该 cell 的 LOD 网格被"近场让位"判据丢掉了；</li>
     *   <li>{@code c} 仅覆盖表说覆盖，没有任何已上传网格落在这个 cell；</li>
     *   <li>{@code .} 什么都没有。</li>
     * </ul>
     * 只看 {@code S} 的分布就能一眼分出"整圈都是 S"（擦除/上传判据）还是
     * "某一侧某一环才是 S"（provider 数据缺口）。</p>
     */
    private static void printSeamMap(double cameraX, double cameraZ, Set<Long> suppressedSections) {
        Minecraft minecraft = Minecraft.getInstance();
        final int radius = 8;
        int cameraCellX = Math.floorDiv((int) Math.floor(cameraX), 64);
        int cameraCellZ = Math.floorDiv((int) Math.floor(cameraZ), 64);
        HashMap<Long, int[]> meshCells = new HashMap<>();
        for (VoxyCompat.LodMesh mesh : VoxyCompat.meshes()) {
            if (!valid(mesh)) continue;
            if (mesh.opaque().length == 0 && mesh.transparent().length == 0) continue;
            int[] stats = meshCells.computeIfAbsent(
                    cellKey(Math.floorDiv(mesh.originX(), 64), Math.floorDiv(mesh.originZ(), 64)),
                    ignored -> new int[2]);
            if (FarFieldGate.lodWindowOpen() && overlapsVanillaWindow(mesh, cameraX, cameraZ)) {
                stats[1]++;
            } else {
                stats[0]++;
            }
        }
        HashSet<Long> uploadedCells = new HashSet<>();
        HashSet<Long> waterCells = new HashSet<>();
        for (Uploaded entry : UPLOADED_VERSIONS.values()) {
            int firstX = Math.floorDiv(entry.originX(), 64);
            int lastX = Math.floorDiv(entry.originX() + entry.width() - 1, 64);
            int firstZ = Math.floorDiv(entry.originZ(), 64);
            int lastZ = Math.floorDiv(entry.originZ() + entry.width() - 1, 64);
            for (int cellX = firstX; cellX <= lastX; cellX++) {
                for (int cellZ = firstZ; cellZ <= lastZ; cellZ++) {
                    long key = cellKey(cellX, cellZ);
                    uploadedCells.add(key);
                    if (entry.waterQuads() > 0) waterCells.add(key);
                }
            }
        }
        HashMap<Long, Integer> erasedCells = new HashMap<>();
        for (long key : suppressedSections) {
            SectionPos pos = SectionPos.of(key);
            erasedCells.merge(cellKey(Math.floorDiv(pos.x(), 4), Math.floorDiv(pos.z(), 4)), 1, Integer::sum);
        }
        System.err.println("[VOXY-LOD MAP] 上=北(-Z) 左=西(-X) cell=64格 rd="
                + FarFieldWindow.renderDistanceChunks() + " seam=" + FarFieldWindow.seamBlocks()
                + " cam=(" + (int) Math.floor(cameraX) + "," + (int) Math.floor(cameraZ) + ")"
                + " cellX=" + cameraCellX + " cellZ=" + cameraCellZ
                + " yaw=" + facing(minecraft)
                + " W=已传且有水面 #=已传但没水 S=原版被擦且无LOD p=已发布未传 x=被让位判据丢弃 c=仅覆盖表 .=无");
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder("[VOXY-LOD MAP] ");
            int rowCellZ = cameraCellZ + dz;
            for (int dx = -radius; dx <= radius; dx++) {
                int cellX = cameraCellX + dx;
                int cellZ = rowCellZ;
                long key = cellKey(cellX, cellZ);
                int[] stats = meshCells.get(key);
                if (waterCells.contains(key)) {
                    row.append('W');
                } else if (uploadedCells.contains(key)) {
                    row.append('#');
                } else if (erasedCells.getOrDefault(key, 0) > 0) {
                    row.append('S');
                } else if (stats != null && stats[0] > 0) {
                    row.append('p');
                } else if (stats != null && stats[1] > 0) {
                    row.append('x');
                } else if (coveredLodCells.contains(key)) {
                    row.append('c');
                } else {
                    row.append('.');
                }
            }
            System.err.println(row.append("  z=").append(rowCellZ * 64));
        }
    }

    /** 小端 u16 读取（桥的顶点是 16 字节小端打包）。 */
    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    

    private static long levelReportTicks;

    private static void reportLevels() {
        if ((++levelReportTicks % 600L) != 0L) return;
        List<VoxyCompat.LodMesh> meshes = VoxyCompat.meshes();
        if (meshes.isEmpty()) return;
        final int stride = 7;
        int[] buckets = new int[4 * stride];
        for (int level = 0; level < 4; level++) {
            buckets[level * stride + 4] = Integer.MAX_VALUE;
        }
        for (VoxyCompat.LodMesh mesh : meshes) {
            if (!valid(mesh)) continue;
            int width = mesh.width();
            int level = Integer.numberOfTrailingZeros(width) - 5;
            if (level < 0 || level >= 4) continue;
            int base = level * stride;
            buckets[base]++;
            byte[] transparent = mesh.transparent();
            for (int quad = 0; quad + 64 <= transparent.length; quad += 64) {
                if ((transparent[quad + 14] & 0xFF) != 12) continue;
                int y0 = u16(transparent, quad + 2);
                int y1 = u16(transparent, quad + 18);
                int y2 = u16(transparent, quad + 34);
                if (y0 == y1 && y1 == y2) {
                    buckets[base + 1]++;
                    int surfaceY = mesh.originY() + y0;
                    if (surfaceY < buckets[base + 4]) buckets[base + 4] = surfaceY;
                    if (surfaceY > buckets[base + 5]) buckets[base + 5] = surfaceY;
                } else {
                    buckets[base + 2]++;
                    int x0 = u16(transparent, quad);
                    int x1 = u16(transparent, quad + 16);
                    int x2 = u16(transparent, quad + 32);
                    int z0 = u16(transparent, quad + 4);
                    int z1 = u16(transparent, quad + 20);
                    int z2 = u16(transparent, quad + 36);
                    if ((x0 == x1 && x1 == x2 && (x0 == 0 || x0 == width))
                            || (z0 == z1 && z1 == z2 && (z0 == 0 || z0 == width))) {
                        buckets[base + 6]++;
                    }
                }
                if (u16(transparent, quad + 6) == 0xFFFF) buckets[base + 3]++;
            }
        }
        StringBuilder line = new StringBuilder("[VOXY-LOD LEVELS]");
        for (int level = 0; level < 4; level++) {
            int base = level * stride;
            if (buckets[base] == 0) continue;
            line.append(" | L").append(level)
                    .append(" w=").append(32 << level)
                    .append(" meshes=").append(buckets[base])
                    .append(" waterSurf=").append(buckets[base + 1])
                    .append(" waterVert=").append(buckets[base + 2])
                    .append(" waterVertOnEdge=").append(buckets[base + 6])
                    .append(" waterNoTile=").append(buckets[base + 3]);
            if (buckets[base + 1] > 0) {
                line.append(" surfY=").append(buckets[base + 4])
                        .append("..").append(buckets[base + 5]);
            }
        }
        System.err.println(line);
    }

    /** 64 字节一个 quad，材质字节在顶点 0 的 offset 14（与 voxy 桥的 writeVertex 一致）。 */
    private static String materialHistogram(byte[] vertices) {
        int[] counts = new int[256];
        for (int quad = 0; quad + 64 <= vertices.length; quad += 64) {
            counts[vertices[quad + 14] & 0xFF]++;
        }
        StringBuilder out = new StringBuilder();
        for (int material = 0; material < counts.length; material++) {
            if (counts[material] == 0) continue;
            if (out.length() > 0) out.append(',');
            out.append(material).append(':').append(counts[material]);
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    /** 图集某一格在 mip0 的 alpha 分布，以及 mip4 那一颗 texel 的 alpha（远处 cutout 采的就是它）。 */
    private static String tileAlphaStats(int tile) {
        byte[] atlas = VoxyCompat.lodAtlas();
        int size = VoxyCompat.lodAtlasSize();
        if (atlas == null || size < 16 || atlas.length < (long) size * size * 4) return "atlas=unavailable";
        int baseX = (tile & 0x3F) * 16;
        int baseY = tile / 64 * 16;
        int zero = 0;
        int min = 255;
        long sum = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int alpha = atlas[((baseY + y) * size + baseX + x) * 4 + 3] & 0xFF;
                if (alpha == 0) zero++;
                if (alpha < min) min = alpha;
                sum += alpha;
            }
        }
        int layerOffset = size * size;
        for (int edge = size / 2; edge > size / 16; edge >>= 1) layerOffset += edge * edge;
        int mip4 = atlas[(layerOffset + (tile / 64) * (size / 16) + (tile & 0x3F)) * 4 + 3] & 0xFF;
        return "tileZeroAlpha=" + zero + "/256 tileMinA=" + min
                + " tileAvgA=" + (sum / 256) + " tileMip4A=" + mip4;
    }

    

    private static boolean voxyWasActive;

    /** Render-thread entry point. Native copies data synchronously before this method returns. */
    public static void tick() {
        if (PERF) perfSample();
        Minecraft minecraft = Minecraft.getInstance();
        if (world != minecraft.level) {
            clearNative();
            world = minecraft.level;
        }

        long partStart = PERF ? System.nanoTime() : 0L;
        VoxyCompat.tick();
        if (PERF) {
            long partEnd = System.nanoTime();
            perfAdd(PERF_BRIDGE, partEnd - partStart);
            partStart = partEnd;
        }
        if ((++diagnosticTicks % 600L) == 0L) {
            System.err.println("[VOXY-LOD] active=" + VoxyCompat.active()
                    + " revision=" + VoxyCompat.revision()
                    + " meshes=" + VoxyCompat.meshes().size()
                    + " uploaded=" + UPLOADED_VERSIONS.size()
                    + " pending=" + PENDING_UPLOADS.size()
                    + " providing=" + lodProviding);
        }
        if (minecraft.level == null || !VoxyCompat.active()) {
            if (voxyWasActive
                    || !UPLOADED_VERSIONS.isEmpty()
                    || !PENDING_UPLOADS.isEmpty()) {
                voxyWasActive = false;
                clearNative();
            }
            return;
        }
        voxyWasActive = true;

        long atlasStart = PERF ? System.nanoTime() : 0L;
        publishAtlas();
        if (PERF) {
            long atlasEnd = System.nanoTime();
            perfAdd(PERF_ATLAS, atlasEnd - atlasStart);
            partStart = atlasEnd;
        }

        long sourceRevision = VoxyCompat.revision();
        var camera = VoxyCompat.cameraPosition(minecraft);
        if (camera == null) return;
        long windowX = Math.floorDiv((int) Math.floor(camera.x), FarFieldGate.LOD_SECTION_BLOCKS);
        long windowZ = Math.floorDiv((int) Math.floor(camera.z), FarFieldGate.LOD_SECTION_BLOCKS);
        if (sourceRevision != observedRevision
                || windowX != observedWindowX || windowZ != observedWindowZ) {
            observedRevision = sourceRevision;
            observedWindowX = windowX;
            observedWindowZ = windowZ;
            List<VoxyCompat.LodMesh> meshes = VoxyCompat.meshes();
            Set<Long> visibleSources = new HashSet<>(meshes.size());
            Set<Long> windowDropped = new HashSet<>();
            List<VoxyCompat.LodMesh> candidates = new ArrayList<>(meshes.size());
            boolean providing = false;
            for (VoxyCompat.LodMesh mesh : meshes) {
                if (!valid(mesh)) continue;
                if (overlapsVanillaWindow(mesh, camera.x, camera.z)) {
                    windowDropped.add(mesh.key());
                    continue;
                }
                visibleSources.add(mesh.key());
                boolean hasGeometry = mesh.opaque().length > 0 || mesh.transparent().length > 0;
                providing |= hasGeometry;
                if (hasGeometry) candidates.add(mesh);
                Uploaded uploaded = UPLOADED_VERSIONS.get(mesh.key());
                if (!farFieldSuppressed && (uploaded == null || uploaded.version() != mesh.version())) {
                    PENDING_UPLOADS.put(mesh.key(), mesh);
                }
            }
            lodProviding = providing;
            coverageCandidates = candidates;
            refreshCoverage();

            PENDING_UPLOADS.keySet().removeIf(key -> !visibleSources.contains(key));
            UPLOADED_VERSIONS.keySet().removeIf(key -> {
                if (visibleSources.contains(key)) return false;
                NativeVulkan.removeVoxyLodMesh(key);
                if (windowDropped.contains(key)) {
                    perfRetractWindow++;
                } else {
                    perfRetractGone++;
                }
                return true;
            });
        }
        FarFieldGate.onLodObservation("voxy", lodProviding);

        int uploads = 0;
        Iterator<Map.Entry<Long, VoxyCompat.LodMesh>> pending = PENDING_UPLOADS.entrySet().iterator();
        while (!farFieldSuppressed && pending.hasNext() && uploads < MAX_UPLOADS_PER_FRAME) {
            VoxyCompat.LodMesh mesh = pending.next().getValue();
            diagnoseMesh(mesh);
            NativeVulkan.uploadVoxyLodMesh(mesh.key(), mesh.version(), mesh.originX(), mesh.originY(),
                    mesh.originZ(), mesh.width(), mesh.dataPointWidth(), mesh.opaque(), mesh.transparent());
            if (LOG_UPLOADS) {
                System.err.println("[VOXY-LOD] upload key=" + mesh.key() + " version=" + mesh.version()
                        + " opaqueBytes=" + mesh.opaque().length + " transparentBytes=" + mesh.transparent().length);
            }
            UPLOADED_VERSIONS.put(mesh.key(), new Uploaded(mesh.version(), mesh.originX(), mesh.originZ(),
                    mesh.width(), mesh.opaque().length / 64, mesh.transparent().length / 64,
                    countWaterQuads(mesh.transparent())));
            perfUploadMeshes++;
            perfUploadBytes += mesh.opaque().length + mesh.transparent().length;
            pending.remove();
            uploads++;
        }
        if (uploads > 0) refreshCoverage();
        if (PERF) perfAdd(PERF_LOD, System.nanoTime() - partStart);
        if (!DIAG) return;
        reportLevels();
        reportRings();
        reportSeam();
    }

    /**
     * 帧时间采样 + A/B 标记文件轮询（每帧一次，见类顶部的"性能取证"说明）。
     *
     * <p>帧时间取的是 {@link #tick()} 两次调用之间的墙钟间隔。{@code tick()} 挂在
     * {@code LevelRenderer.render} 的 HEAD（LevelRendererMixin:49-61），即**每帧一次**，
     * 所以这个间隔就是渲染循环的周期（开帧生成时是"生成帧"的周期，正是要优化的那个）。</p>
     */
    private static void perfSample() {
        long now = System.nanoTime();
        if (perfLastNanos != 0L) {
            PERF_DELTAS[perfCount++] = now - perfLastNanos;
            if (perfCount >= PERF_WINDOW_FRAMES) {
                reportPerf();
                perfCount = 0;
                perfUploadMeshes = 0;
                perfUploadBytes = 0;
            }
        }
        perfLastNanos = now;
        if ((++perfMarkerTicks % AB_CHECK_FRAMES) == 0 && updateAbState()) {
            if (perfCount > 0) {
                reportPerf();
                perfCount = 0;
                perfUploadMeshes = 0;
                perfUploadBytes = 0;
            }
            perfLastNanos = 0L;
        }
    }

    private static void reportPerf() {
        long[] sorted = Arrays.copyOf(PERF_DELTAS, perfCount);
        Arrays.sort(sorted);
        long sum = 0L;
        for (long value : sorted) sum += value;
        double avgMs = sum / (double) perfCount / 1e6;
        double p1Ms = sorted[Math.min(perfCount - 1, (int) (perfCount * 0.99))] / 1e6;
        double maxMs = sorted[perfCount - 1] / 1e6;
        long opaque = 0L, alpha = 0L, water = 0L;
        long[] byWidth = new long[4];
        for (Uploaded entry : UPLOADED_VERSIONS.values()) {
            opaque += entry.opaqueQuads();
            alpha += entry.transparentQuads();
            water += entry.waterQuads();
            int bucket = entry.width() <= 32 ? 0 : entry.width() <= 64 ? 1 : entry.width() <= 128 ? 2 : 3;
            byWidth[bucket] += entry.opaqueQuads() + entry.transparentQuads();
        }
        long vertexMB = (opaque + alpha) * 6L * 48L >> 20;
        HashSet<Long> vanillaInRt = new HashSet<>();
        HashSet<Long> vanillaErased = new HashSet<>();
        FarFieldGate.collectStates(vanillaInRt, vanillaErased);
        var camera = VoxyCompat.cameraPosition(Minecraft.getInstance());
        String pos = camera == null ? "?" : (Math.round(camera.x) + "," + Math.round(camera.z));
        StringBuilder javaParts = new StringBuilder();
        double javaTotal = 0.0;
        for (int part = 0; part < PERF_PART_NAMES.length; part++) {
            double partMs = PERF_PART_NANOS[part] / (double) perfCount / 1e6;
            PERF_PART_NANOS[part] = 0L;
            javaTotal += partMs;
            if (partMs >= 0.005) {
                javaParts.append(' ').append(PERF_PART_NAMES[part])
                        .append('=').append(Math.round(partMs * 100) / 100.0);
            }
        }
        System.err.println("[LOD PERF] frame ms avg=" + (Math.round(avgMs * 100) / 100.0)
                + " p1=" + (Math.round(p1Ms * 100) / 100.0)
                + " max=" + (Math.round(maxMs * 100) / 100.0)
                + " | java=" + (Math.round(javaTotal * 100) / 100.0) + "ms" + javaParts
                + " | farField=" + (farFieldSuppressed ? "OFF(A/B)" : "ON")
                + " meshes=" + UPLOADED_VERSIONS.size()
                + " quads=" + opaque + "+" + alpha + "(water " + water + ")"
                + " vertexMB=" + vertexMB
                + " bySecW{32,64,128,256}=" + byWidth[0] + "," + byWidth[1] + ","
                + byWidth[2] + "," + byWidth[3]
                + " | uploadWindow meshes=" + perfUploadMeshes + " MB=" + (perfUploadBytes >> 20)
                + " retract=" + perfRetractWindow + "/" + perfRetractGone
                + " | vanilla inRt=" + vanillaInRt.size() + " erased=" + vanillaErased.size()
                + " | pos=" + pos);
    }

    /** 自动 A/B 的每段秒数：`-Dorion.lod.ab=<秒>`，未给则 60；0/非法值按 60。 */
    private static int abAutoSeconds() {
        String value = System.getProperty("orion.lod.ab");
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        return 60;
    }

    /**
     * A/B 开关状态推进。返回 true 表示**这一次发生了切换**（调用方据此切断当前统计窗口）。
     *
     * <p>两种触发方式，优先自动：</p>
     * <ul>
     *   <li>运行目录里有 {@code orion_lod_ab} ⇒ 按墙钟每 60 秒（或
     *       {@code -Dorion.lod.ab=<秒>}）自动在"停供/恢复"之间交替。这么设计是因为
     *       手动建/删文件要求用户在游戏里切出去操作，一次没做成（2026-09-26 实测：日志里
     *       一条 OFF 行都没有，工作区里也找不到那个文件）整轮数据就白跑；
     *       自动交替让"站着不动"这一件事就能同时拿到两段读数。</li>
     *   <li>否则看 {@code orion_lod_off} 文件是否存在 ⇒ 停供远场几何。</li>
     * </ul>
     *
     * <p>⚠ 只撤几何，**故意不动** {@code lodProviding} 与覆盖表 —— 原版侧的接管状态
     * 在 A/B 两侧必须逐 section 一致，否则差的就不只是远场几何了（用户上一轮用
     * "视距调到 2"做的对照就是这个毛病）。</p>
     */
    private static boolean updateAbState() {
        boolean want;
        String reason;
        boolean auto;
        try {
            auto = java.nio.file.Files.exists(java.nio.file.Path.of(AB_AUTO_MARKER));
        } catch (Throwable ignored) {
            return false;
        }
        if (auto) {
            int seconds = abAutoSeconds();
            want = ((System.currentTimeMillis() / 1000L) / seconds) % 2L != 0L;
            reason = "自动 A/B 每 " + seconds + " 秒交替";
        } else {
            try {
                want = java.nio.file.Files.exists(java.nio.file.Path.of(AB_MARKER));
            } catch (Throwable ignored) {
                return false;
            }
            reason = "标记文件 " + AB_MARKER;
        }
        if (want == farFieldSuppressed) return false;
        farFieldSuppressed = want;
        if (want) {
            if (!UPLOADED_VERSIONS.isEmpty()) NativeVulkan.clearVoxyLodMeshes();
            UPLOADED_VERSIONS.clear();
            PENDING_UPLOADS.clear();
        } else {
            observedRevision = Long.MIN_VALUE;
        }
        System.err.println("[LOD PERF] 远场几何 " + (want ? "已停供" : "已恢复")
                + "（" + reason + "；原版接管状态保持不变）");
        return true;
    }

    public static void clearNative() {
        if (!UPLOADED_VERSIONS.isEmpty()) {
            NativeVulkan.clearVoxyLodMeshes();
            UPLOADED_VERSIONS.clear();
        }
        PENDING_UPLOADS.clear();
        observedRevision = Long.MIN_VALUE;
        observedWindowX = Long.MIN_VALUE;
        observedWindowZ = Long.MIN_VALUE;
        lodProviding = false;
        coveredLodCells = Set.of();
        coverageCandidates = List.of();
        uploadedAtlasHash = 0;
        FarFieldGate.onLodHardReset();
    }

    /**
     * 把桥接端的 mip 链图集推给原生侧,必须先于本 tick 的网格上传(首帧描述符
     * 就绪)。原生侧按内容哈希判等,重复发送零成本;每 600 tick 重发一次作为
     * 设备重建(F3+T 等)后的保险。
     */
    private static void publishAtlas() {
        if ((diagnosticTicks & 31L) != 0L) return;
        byte[] atlasRef = VoxyCompat.lodAtlasDirect();
        if (atlasRef == null || atlasRef.length == 0) return;
        int hash = Arrays.hashCode(atlasRef);
        if (hash == uploadedAtlasHash && (diagnosticTicks % 600L) != 0L) return;

        byte[] atlas = VoxyCompat.lodAtlas();
        int size = VoxyCompat.lodAtlasSize();
        if (atlas == null || atlas.length == 0 || size <= 0) return;
        hash = Arrays.hashCode(atlas);
        if (hash == uploadedAtlasHash && (diagnosticTicks % 600L) != 0L) return;
        int mipLevels = 0;
        long remaining = atlas.length;
        for (int edge = size; edge >= 1 && remaining > 0; edge >>= 1) {
            long layerBytes = (long) edge * edge * 4L;
            if (layerBytes > remaining) return;
            remaining -= layerBytes;
            mipLevels++;
        }
        if (remaining != 0 || mipLevels == 0) return;
        if (!NativeVulkan.uploadVoxyLodAtlas(atlas, size, mipLevels)) return;
        uploadedAtlasHash = hash;
    }

    private static boolean valid(VoxyCompat.LodMesh mesh) {
        return mesh.width() > 0 && mesh.dataPointWidth() > 0
                && mesh.opaque().length <= MAX_LOD_BYTES_PER_MESH
                && mesh.transparent().length <= MAX_LOD_BYTES_PER_MESH
                && (mesh.opaque().length & 63) == 0
                && (mesh.transparent().length & 63) == 0;
    }

    /**
     * voxy 接管远场:原版近场渲染到动态缝 rd 环,LOD 网格按各自截面宽在格空间
     * 判重叠,环*截面宽 &lt; seam 格(seam=ceil(rd/4)*64)的与近场重叠过滤掉。
     * rd&lt;2 时窗口关闭,原版渲染一切,LOD 全部让位。截面宽随桥的 LOD 金字塔
     * 分层(64/128/256),同一判定对各级都成立。
     *
     * <p>⭐ 2026-09-26 重写为**方块矩形求交**（用户报"视距内区块也会被 LOD 覆盖，冲突然后卡顿"）。
     * 旧判据是**格环**：{@code ring * width < seamLodSections * 64}，其中
     * {@code ring = |mesh.origin/width - camBase/width|}。它只丢"整格落在 seam 之内"的网格，
     * 而近场**实际会画**的方块范围由 {@code FarFieldGate.updateNearby} 的
     * {@code distance < seamSections()} 决定，即 {@code [camBase-(seam-1)*16, camBase+(seam-1)*16+15]}
     * （rd=10 时 = {@code [camBase-112, camBase+127]}）。两者对小网格几乎重合，
     * 但 {@code width ≥ 128} 的网格**一格就 128 格宽**，会跨在窗口边界上：
     * 相机在 {@code camBase+64} 时，一个 {@code [camBase+64, camBase+191]} 的网格被判定为
     * "在 seam 之外"而保留 ⇒ **LOD 直接压在近场窗口里最多 190 格**。rd 越小越糟
     * （rd=4 时窗口仅 112 格，LOD 能盖掉近一半），实机表现为近场区块被 LOD 覆盖、
     * 两层几何在同一位置互相打架（谁近谁赢 ⇒ 闪烁 + 冲突）。
     *
     * <p>现在的判据：网格的方块矩形与"近场一定会画的方块矩形"**相交**就丢。边界与
     * {@code FarFieldGate} 逐格同源（同一 {@code camBase}、同一 {@code seamSections()}），
     * 不再有隔着边界的半格。丢掉粗网格不会造成空洞：桥的环规划里每一环的内侧band
     * 都由**更细的一级**覆盖（L0 0-128、L1 128-426、L2 426-725、L3 725-1024），
     * 所以 [窗口外沿, 粗网格外沿) 这一段始终有更细的网格接着。</p>
     *
     * <p>⭐ 16 格"迟滞带"已于 2026-09-26 清除（用户报"视距内区块会反复和 LOD 冲突撞色"）：
     * {@code updateNearby} 的**擦除**阈值本就该与 {@code shouldSuppress} 的**压制**阈值同值，
     * 原先擦除要 {@code distance ≥ seam+1}、压制却是 {@code ≥ seam}，于是
     * {@code |sec-base| == seam} 那一圈变成"不擦也不恢复"——两层都会画。现在改成
     * {@code ≥ seam}，边界确定为：{@code ≤ seam-1} 归近场、{@code ≥ seam} 归 LOD。</p>
     *
     * <p>⭐ 同一个 bug 还有第二半：本判据过去只在 {@code VoxyCompat.revision()} 变化时重算，
     * 而**玩家只移动、voxy 没有新数据**时 revision 不变 ⇒ 相机跨过 64 格边界后窗口整体平移
     * 却不重算，新落进窗口的**已上传**网格不会被 {@code removeVoxyLodMesh} 撤下。
     * 现在相机所在的 64 格对齐格也进重算条件（见 {@link #observedWindowX}）。</p>
     */
    private static boolean overlapsVanillaWindow(VoxyCompat.LodMesh mesh, double cameraX, double cameraZ) {
        if (!FarFieldGate.lodWindowOpen()) return true;
        int width = mesh.width();
        if (width <= 0) return true;
        int camBaseX = Math.floorDiv((int) Math.floor(cameraX), FarFieldGate.LOD_SECTION_BLOCKS)
                * FarFieldGate.LOD_SECTION_BLOCKS;
        int camBaseZ = Math.floorDiv((int) Math.floor(cameraZ), FarFieldGate.LOD_SECTION_BLOCKS)
                * FarFieldGate.LOD_SECTION_BLOCKS;
        int seamBlocks = FarFieldGate.seamSections() * BLOCKS_PER_CHUNK;
        int keepMinX = camBaseX - (seamBlocks - BLOCKS_PER_CHUNK);
        int keepMaxX = camBaseX + seamBlocks - 1;
        int keepMinZ = camBaseZ - (seamBlocks - BLOCKS_PER_CHUNK);
        int keepMaxZ = camBaseZ + seamBlocks - 1;
        int meshMaxX = mesh.originX() + width - 1;
        int meshMaxZ = mesh.originZ() + width - 1;
        return meshMaxX >= keepMinX && mesh.originX() <= keepMaxX
                && meshMaxZ >= keepMinZ && mesh.originZ() <= keepMaxZ;
    }
}
