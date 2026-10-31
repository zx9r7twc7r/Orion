package com.example.client.compat;

import com.example.client.mixin.GameRendererCameraAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;

/**
 * Optional, reflection-only bridge for the Orion Voxy provider ABI.
 *
 * <p>The normal Voxy release does not expose this ABI. Keeping every Voxy
 * symbol behind reflection means this mod remains loadable without Voxy, and
 * with a normal Voxy build that does not implement the provider.</p>
 */
public final class VoxyCompat {
    private static final Api API = FabricLoader.getInstance().isModLoaded("voxy") ? Api.create() : null;
    private static volatile List<LodMesh> snapshot = List.of();
    private static volatile long revision;
    private static long diagnosticTicks;
    private static volatile long observedSourceRevision = Long.MIN_VALUE;
    private static volatile int observedSnapshotHash;
    private static volatile int renderDistanceChunks;
    private static volatile boolean warned;

    private VoxyCompat() {
    }

    public record LodMesh(long key, long version, int originX, int originY, int originZ,
                          int width, int dataPointWidth, byte[] opaque, byte[] transparent) {
    }

    public static boolean available() {
        return API != null;
    }

    public static boolean active() {
        Api api = API;
        if (api == null) return false;
        if (api.configuredEnabled == null) return true;
        try {
            return Boolean.TRUE.equals(api.configuredEnabled.invoke(null))
                    && (api.configuredIngestEnabled == null
                    || Boolean.TRUE.equals(api.configuredIngestEnabled.invoke(null)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Called only from the render thread. Workers consume the immutable snapshot. */
    public static void tick() {
        Api api = API;
        Minecraft minecraft = Minecraft.getInstance();
        if (api == null || minecraft.level == null || minecraft.gameRenderer == null || !active()) {
            clearLocalSnapshot();
            return;
        }
        try {
            Vec3 camera = cameraPosition(minecraft);
            if (camera == null) return;
            Object result = api.invokePoll(camera.x, camera.y, camera.z,
                    minecraft.options.renderDistance().get(), minecraft.level.getMinY(), minecraft.level.getMaxY());
            long sourceRevision = ((Number) api.revision.invoke(null)).longValue();
            renderDistanceChunks = Math.max(0, ((Number) api.renderDistanceChunks.invoke(null)).intValue());
            List<?> source = asList(result);
            if ((++diagnosticTicks % 600L) == 0L) {
                System.err.println("[VOXY-BRIDGE] available=true active=true sourceRevision="
                        + sourceRevision + " meshes=" + source.size()
                        + " renderDistance=" + renderDistanceChunks);
            }
            int cheapFingerprint = sourceFingerprint(source, api);
            if (sourceRevision == observedSourceRevision && cheapFingerprint == observedSnapshotHash) {
                return;
            }
            ArrayList<LodMesh> converted = new ArrayList<>(source.size());
            for (Object mesh : source) {
                if (mesh == null) continue;
                Access access = api.access(mesh.getClass());
                byte[] opaque = (byte[]) access.opaque.invoke(mesh);
                byte[] transparent = (byte[]) access.transparent.invoke(mesh);
                converted.add(new LodMesh(
                        ((Number) access.key.invoke(mesh)).longValue(),
                        ((Number) access.version.invoke(mesh)).longValue(),
                        ((Number) access.originX.invoke(mesh)).intValue(),
                        ((Number) access.originY.invoke(mesh)).intValue(),
                        ((Number) access.originZ.invoke(mesh)).intValue(),
                        ((Number) access.width.invoke(mesh)).intValue(),
                        ((Number) access.dataPointWidth.invoke(mesh)).intValue(),
                        opaque == null ? new byte[0] : opaque.clone(),
                        transparent == null ? new byte[0] : transparent.clone()));
            }
            List<LodMesh> next = List.copyOf(converted);
            int snapshotHash = snapshotFingerprint(next);
            if (sourceRevision != observedSourceRevision || snapshotHash != observedSnapshotHash) {
                snapshot = next;
                observedSourceRevision = sourceRevision;
                observedSnapshotHash = snapshotHash;
                revision++;
            }
        } catch (Throwable failure) {
            clearLocalSnapshot();
            if (!warned) {
                warned = true;
                System.err.println("[VOXY] Orion-provider bridge is unavailable or incompatible: " + failure);
            }
        }
    }

    /** Accept the bridge's ABI without imposing a concrete collection type on Voxy. */
    private static List<?> asList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List<?> list) return list;
        if (result instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (result instanceof Iterable<?> iterable) {
            ArrayList<Object> values = new ArrayList<>();
            for (Object value : iterable) values.add(value);
            return values;
        }
        if (result.getClass().isArray()) {
            int length = Array.getLength(result);
            ArrayList<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) values.add(Array.get(result, i));
            return values;
        }
        return List.of();
    }

    /**
     * 与 {@link #snapshotFingerprint} **同构同值**，但**不克隆字节数组**：直接从提供方对象上取
     * (key, version, 两桶长度)。给每帧的"有没有变"前置判断用，避免每帧克隆整份远场几何。
     *
     * <p>逐项顺序、hash 初值、null 网格跳过方式都必须和 {@link #snapshotFingerprint} 一致，
     * 否则两边算出的值不可比（{@code VoxyCompat.tick} 正是拿它跟 {@code observedSnapshotHash}
     * 比的）。取不到字段时返回 {@code Integer.MIN_VALUE}（必然不等）⇒ 走原来那条重路径，保守。</p>
     */
    private static int sourceFingerprint(List<?> source, Api api) {
        int hash = 1;
        for (Object mesh : source) {
            if (mesh == null) continue;
            try {
                Access access = api.access(mesh.getClass());
                byte[] opaque = (byte[]) access.opaque.invoke(mesh);
                byte[] transparent = (byte[]) access.transparent.invoke(mesh);
                hash = 31 * hash + Long.hashCode(((Number) access.key.invoke(mesh)).longValue());
                hash = 31 * hash + Long.hashCode(((Number) access.version.invoke(mesh)).longValue());
                hash = 31 * hash + (opaque == null ? 0 : opaque.length);
                hash = 31 * hash + (transparent == null ? 0 : transparent.length);
            } catch (Throwable ignored) {
                return Integer.MIN_VALUE;
            }
        }
        return hash;
    }

    private static int snapshotFingerprint(List<LodMesh> meshes) {
        int hash = 1;
        for (LodMesh mesh : meshes) {
            hash = 31 * hash + Long.hashCode(mesh.key());
            hash = 31 * hash + Long.hashCode(mesh.version());
            hash = 31 * hash + mesh.opaque().length;
            hash = 31 * hash + mesh.transparent().length;
        }
        return hash;
    }

    public static List<LodMesh> meshes() {
        return snapshot;
    }

    public static long revision() {
        return revision;
    }

    public static int renderDistanceChunks() {
        return renderDistanceChunks;
    }

    /**
     * Packed mip-chain RGBA8 texture atlas of the provider's LOD quads, or
     * null when the provider predates the atlas ABI.  The native side owns
     * the resulting image; callers re-send on world/pack changes.
     */
    public static byte[] lodAtlas() {
        Api api = API;
        if (api == null || api.lodAtlas == null) return null;
        try {
            byte[] pixels = (byte[]) api.lodAtlas.invoke(null);
            return pixels == null ? null : pixels.clone();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Zero-copy read of the provider's atlas array for cheap change checks
     * (hashing).  The provider keeps the array stable except on a texture
     * rebuild; callers that hand the array to JNI must still go through
     * {@link #lodAtlas()} for the defensive clone.
     */
    public static byte[] lodAtlasDirect() {
        Api api = API;
        if (api == null || api.lodAtlas == null) return null;
        try {
            return (byte[]) api.lodAtlas.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Atlas edge length in pixels (mip0), or 0 when unavailable. */
    public static int lodAtlasSize() {
        Api api = API;
        if (api == null || api.lodAtlasSize == null) return 0;
        try {
            return Math.max(0, ((Number) api.lodAtlasSize.invoke(null)).intValue());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Optional control surface used by the settings screen when the provider exposes it. */
    public static boolean setEnabled(boolean enabled) {
        Api api = API;
        if (api == null || api.setEnabled == null) return false;
        try {
            api.setEnabled.invoke(null, enabled);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setRenderDistanceChunks(int chunks) {
        Api api = API;
        if (api == null || api.setRenderDistanceChunks == null || chunks < 0) return false;
        try {
            api.setRenderDistanceChunks.invoke(null, chunks);
            renderDistanceChunks = chunks;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Vec3 cameraPosition(Minecraft minecraft) {
        if (minecraft.gameRenderer == null) return null;
        return ((GameRendererCameraAccessor) minecraft.gameRenderer).rtx$getMainCamera().position();
    }

    public static boolean reset() {
        clearLocalSnapshot();
        if (API == null) return false;
        try {
            API.reset.invoke(null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearLocalSnapshot() {
        if (!snapshot.isEmpty() || observedSourceRevision != Long.MIN_VALUE) {
            snapshot = List.of();
            revision++;
        }
        observedSourceRevision = Long.MIN_VALUE;
        observedSnapshotHash = 0;
        renderDistanceChunks = 0;
    }

    private record Access(Method key, Method version, Method originX, Method originY, Method originZ,
                          Method width, Method dataPointWidth, Method opaque, Method transparent) {
        static Access create(Class<?> type) throws ReflectiveOperationException {
            return new Access(type.getMethod("key"), type.getMethod("version"),
                    type.getMethod("originX"), type.getMethod("originY"), type.getMethod("originZ"),
                    type.getMethod("width"), type.getMethod("dataPointWidth"),
                    type.getMethod("opaque"), type.getMethod("transparent"));
        }
    }

    private static final class Api {
        final Method poll;
        final boolean pollUsesFloat;
        final Method revision;
        final Method renderDistanceChunks;
        final Method reset;
        final Method configuredEnabled;
        final Method configuredIngestEnabled;
        final Method setEnabled;
        final Method setRenderDistanceChunks;
        final Method lodAtlas;
        final Method lodAtlasSize;
        private volatile Class<?> meshType;
        private volatile Access meshAccess;

        private Api(Class<?> bridge) throws ReflectiveOperationException {
            Method doublePoll = findOptional(bridge,
                    new Class<?>[]{double.class, double.class, double.class, int.class, int.class, int.class}, "poll");
            Method floatPoll = findOptional(bridge,
                    new Class<?>[]{float.class, float.class, float.class, int.class, int.class, int.class}, "poll");
            if (doublePoll == null && floatPoll == null) throw new NoSuchMethodException("poll");
            poll = doublePoll != null ? doublePoll : floatPoll;
            pollUsesFloat = doublePoll == null;
            revision = bridge.getMethod("revision");
            renderDistanceChunks = bridge.getMethod("renderDistanceChunks");
            reset = bridge.getMethod("reset");
            configuredEnabled = findOptional(bridge, "configuredEnabled", "enabled", "isEnabled");
            configuredIngestEnabled = findOptional(bridge, "configuredIngestEnabled");
            setEnabled = findOptional(bridge, new Class<?>[]{boolean.class},
                    "setConfiguredEnabled", "setEnabled");
            setRenderDistanceChunks = findOptional(bridge, new Class<?>[]{int.class},
                    "setConfiguredRenderDistanceChunks", "setRenderDistanceChunks", "setRenderDistance");
            lodAtlas = findOptional(bridge, "lodAtlas");
            lodAtlasSize = findOptional(bridge, "lodAtlasSize");
        }

        Object invokePoll(double x, double y, double z, int vanillaChunks, int minY, int maxY)
                throws ReflectiveOperationException {
            if (pollUsesFloat) {
                return poll.invoke(null, (float) x, (float) y, (float) z, vanillaChunks, minY, maxY);
            }
            return poll.invoke(null, x, y, z, vanillaChunks, minY, maxY);
        }

        private static Method findOptional(Class<?> type, String... names) {
            return findOptional(type, new Class<?>[0], names);
        }

        private static Method findOptional(Class<?> type, Class<?>[] parameterTypes, String... names) {
            for (String name : names) {
                try {
                    return type.getMethod(name, parameterTypes);
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }

        static Api create() {
            try {
                Class<?> bridge;
                try {
                    bridge = Class.forName("me.cortex.voxy.client.compat.CausticaBridge");
                } catch (ClassNotFoundException missingCausticaName) {
                    bridge = Class.forName("me.cortex.voxy.client.compat.VoxyCausticaBridge");
                }
                if (!Boolean.TRUE.equals(bridge.getMethod("available").invoke(null))) return null;
                return new Api(bridge);
            } catch (Throwable ignored) {
                return null;
            }
        }

        Access access(Class<?> type) throws ReflectiveOperationException {
            Access current = meshAccess;
            if (current != null && meshType == type) return current;
            synchronized (this) {
                if (meshAccess == null || meshType != type) {
                    meshAccess = Access.create(type);
                    meshType = type;
                }
                return meshAccess;
            }
        }
    }
}
