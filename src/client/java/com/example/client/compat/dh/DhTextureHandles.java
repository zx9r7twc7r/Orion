package com.example.client.compat.dh;

import com.example.vulkan.NativeVulkan;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 DH 的远场纹理句柄推进 C++（P1 图像通路的第一步）。
 *
 * <h2>句柄长什么样（P0 实测，见规划文档 §11.1）</h2>
 * <p>{@code getDhColorTextureBlazeWrapper().getWrappedMcObject()} 返回的是
 * <b>{@code Object[3]}</b>，三个元素分别是 renderpearl 的 {@code VulkanGpuTexture} /
 * {@code VulkanGpuTextureView} / {@code VulkanGpuSampler}。真正有用的是两个 long：</p>
 * <ul>
 *   <li>{@code VulkanGpuTextureView.vkImageView} —— 与既有 atlas 直通先例
 *       （{@code VulkanRenderPassAtlasHijackMixin} 里的 {@code VulkanGpuTextureView.vkImageView()}）
 *       完全同款；</li>
 *   <li>{@code VulkanGpuSampler.vkSampler}。</li>
 * </ul>
 * <p>另外还取 {@code VulkanGpuTexture.vkImage}（给以后要自己建 view 的场合留的）
 * 与 {@code VulkanGpuTexture.device.vkDevice}——后者是<b>设备同源性的判据</b>，C++ 侧
 * 拿它和 {@code g_device} 做一次纯指针比较即可。</p>
 *
 * <h2>为什么要用反射而不是强转</h2>
 * <p>这些类是 renderpearl（MC 自己的 Vulkan 后端）的内部实现，跨小版本随时可能改名。
 * 强转会把一个版本漂移变成 {@code NoSuchMethodError} 崩游戏；反射只会退化成
 * "这次没拿到句柄"，然后每 2 秒再试一次（见 {@link #RETRY_INTERVAL_FRAMES}）。</p>
 *
 * <h2>什么时候会改变渲染状态（2026-09-30 起）</h2>
 * <p>成套句柄这条路上，本类是只读的：只读 Java 字段 + 调 native 入口（C++ 侧只做赋值
 * 与指针比较，不产生 Vulkan 调用）。</p>
 * <p>但 {@code rendererMode=DISABLED} 时的常态是走 <b>atlas 直取</b>那条路
 * （见 {@link #fetchAtlasDirect}）：那时会主动调用 DH 的
 * {@code BlazeBlockTextureAtlas.uploadPendingTiles()}，<b>这一次是真会上传 GPU 纹理</b>。
 * 这是必要的——DH 自己的渲染器不会跑，图集就永远建不出来。上传走 DH 自己的
 * {@code COMMAND_ENCODER}（设备级命令编码器），与 DH 正常渲染时同一路径。</p>
 */
final class DhTextureHandles {
    private static final String TAG = "[DH-TEX]";

    //=====================================================================//
    // 反射元数据缓存                                                       //
    //                                                                     //
    // Class/Field/Method 解析结果在会话内是稳定的（DH 版本不变），此前每次     //
    // tick（每 2 秒）都重新 Class.forName + getMethod + getDeclaredField，    //
    // readField 还把 NoSuchFieldException 当控制流用。缓存后取到的对象与       //
    // 之前完全一致，只是不再重复解析。键数量级 = 会话中涉及的 (类, 成员) 对，    //
    // 十几个封顶，无需淘汰。                                                //
    //=====================================================================//
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static volatile Class<?> cachedAtlasClass;

    private static Field cachedPublicField(Class<?> clazz, String name)
            throws NoSuchFieldException {
        String key = clazz.getName() + "#public#" + name;
        Field field = FIELD_CACHE.get(key);
        if (field != null) return field;
        field = clazz.getField(name);
        FIELD_CACHE.put(key, field);
        return field;
    }

    private static Field cachedDeclaredField(Class<?> clazz, String name)
            throws NoSuchFieldException {
        String key = clazz.getName() + "#declared#" + name;
        Field field = FIELD_CACHE.get(key);
        if (field != null) return field;
        field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        FIELD_CACHE.put(key, field);
        return field;
    }

    private static Method cachedMethod(Class<?> clazz, String name)
            throws NoSuchMethodException {
        String key = clazz.getName() + "#" + name;
        Method method = METHOD_CACHE.get(key);
        if (method != null) return method;
        method = clazz.getMethod(name);
        METHOD_CACHE.put(key, method);
        return method;
    }


    /** 句柄发现失败时的重试间隔（≈2 秒 @60fps）。DH 重建纹理后由这里重新推送。 */
    static final long RETRY_INTERVAL_FRAMES = 120L;

    private static long nextCheckFrame;

    /** 上一次成功推送的 view 句柄，用来判断 DH 是否重建了纹理（换世界/改渲染距离）。 */
    private static long publishedColorView;
    private static long publishedDepthView;
    private static long publishedAtlasView;
    private static boolean published;

    private static int pushes;
    private static int failures;

    /**
     * 上一次"没拿到句柄"的完整原因串。
     *
     * <p>用来去重：DH 在它自己不渲染时永远建不出 block ratio atlas，那是<b>预期内</b>的
     * 缺失（我们接管几何时必然如此），每 2 秒刷一行只会淹掉日志。只有原因<b>变了</b>
     * 才打一行，于是"从'未就绪'变成反射失败"这类真故障仍然一眼可见。</p>
     */
    private static String lastMissReason;

    private DhTextureHandles() {
    }

    static String stats() {
        return "推送=" + pushes + " 失败=" + failures + (published ? " 已生效" : " 未生效");
    }

    /**
     * C++ 的 binding 49 是否已真的指向 DH 的 {@code blockRatioAtlas}。
     *
     * <p>远场顶点侧（{@link DhFarFieldUploader}）用它决定顶点要不要带
     * {@code textureTileId}：没生效时 C++ 的 binding 49 是<b>回落绑的原版方块图集</b>，
     * 带着 DH 的 tileId 去采样只会是满屏错色。</p>
     *
     * <p>⭐ 值来自 native 返回值，不是"调用过即真"——C++ 侧可能因"句柄不全 / 设备不同源"
     * 而拒绝（见 {@code publishFull} / {@code publishAtlasOnly} 里的 {@code published = accepted}）。</p>
     */
    static boolean isPublished() {
        return published;
    }

    /**
     * 渲染线程调用：发现句柄并按需推送，每 {@link #RETRY_INTERVAL_FRAMES} 帧一次。
     *
     * <p>成套句柄只在"第一次拿到"或"句柄变了"时真正推送；atlas 直取那条路每次都会
     * 调一次 {@code uploadPendingTiles()}（见 {@link #fetchAtlasDirect}），句柄本身仍只在
     * 视图变化时重推。</p>
     */
    static void tick(IDhApiRenderProxy proxy, long frame) {
        if (!DhFlags.PUBLISH_TEXTURES) return;
        if (proxy == null) return;
        if (frame < nextCheckFrame) return;
        nextCheckFrame = frame + RETRY_INTERVAL_FRAMES;

        // 方案 c 的成套句柄。color/depth 只在 DH 自己渲染时才存在（见类注释），
        // 拿不到就走下面的 atlas 直取。
        Fetch color = fetch("color", () -> proxy.getDhColorTextureBlazeWrapper());
        Fetch depth = fetch("depth", () -> proxy.getDhDepthTextureBlazeWrapper());

        if (color.handle != null && depth.handle != null) {
            Fetch atlas = fetch("blockRatioAtlas",
                    () -> proxy.getDhBlockRatioAtlasTextureBlazeWrapper());
            if (atlas.handle != null) {
                lastMissReason = null;
                publishFull(color, depth, atlas);
                return;
            }
            miss("color[就绪] depth[就绪] atlas[" + atlas.reason + "]");
            return;
        }

        // 常态（rendererMode=DISABLED）：DH 自己不渲染 ⇒ 上面两个 API getter 永远失败，
        // atlas 也不能靠 API 拿。改由我们直接调 DH 的 uploadPendingTiles() 把图集建出来。
        Fetch atlas = fetchAtlasDirect();
        if (atlas.handle == null) {
            miss("color[" + color.reason + "] depth[" + depth.reason
                    + "] 直取[" + atlas.reason + "]");
            return;
        }
        lastMissReason = null;
        publishAtlasOnly(atlas);
    }

    /** 记录一次"没拿到"：计数 + 只在原因变化时打一行（见 {@link #lastMissReason}）。 */
    private static void miss(String reason) {
        failures++;
        if (!reason.equals(lastMissReason)) {
            lastMissReason = reason;
            log("句柄未就绪：" + reason
                    + "（DH 不自己渲染时 color/depth 永远拿不到是预期内的；"
                    + "atlas 由我们调 uploadPendingTiles() 主动建）");
        }
    }

    private static void publishFull(Fetch color, Fetch depth, Fetch atlas) {
        boolean changed = !published
                || color.handle.view != publishedColorView
                || depth.handle.view != publishedDepthView
                || atlas.handle.view != publishedAtlasView;
        if (!changed) return;

        boolean accepted;
        try {
            accepted = NativeVulkan.publishDhTextures(
                    color.handle.image, color.handle.view, color.handle.sampler,
                    depth.handle.image, depth.handle.view, depth.handle.sampler,
                    atlas.handle.image, atlas.handle.view, atlas.handle.sampler,
                    color.handle.device, color.handle.width, color.handle.height);
        } catch (Throwable failure) {
            failures++;
            log("推送失败（native 调用异常）：" + failure);
            return;
        }

        // ⚠ 必须看 native 的返回值：C++ 侧可能因为"句柄不全 / 设备不同源"而拒绝，
        // 那时如果这里仍记成已发布，顶点会带上真 tileId 而 binding 49 还指着回落图集
        // ⇒ 满屏错色（09-25 就是这么崩过一次，见 DhFlags.PUBLISH_TEXTURES 的注释）。
        published = accepted;
        publishedColorView = color.handle.view;
        publishedDepthView = depth.handle.view;
        publishedAtlasView = atlas.handle.view;
        pushes++;
        log("推送成套第 " + pushes + " 次：colorView=0x" + Long.toHexString(color.handle.view)
                + " depthView=0x" + Long.toHexString(depth.handle.view)
                + " atlasView=0x" + Long.toHexString(atlas.handle.view)
                + " 尺寸=" + color.handle.width + "x" + color.handle.height
                + " ⇒ C++ 判定可用=" + accepted + "（细节见 rt_debug.log 的 [DH TEX] 行）");
    }

    private static void publishAtlasOnly(Fetch atlas) {
        // view 没变就不用重推（内容增长但尺寸不变时描述符也无需改）。每 2 秒仍会走到
        // 这里一次——那是为了让 fetchAtlasDirect 里的 uploadPendingTiles() 持续把
        // 新注册的 tile 刷进图集，不是为了重推句柄。
        if (published && atlas.handle.view == publishedAtlasView) return;

        boolean accepted;
        try {
            accepted = NativeVulkan.publishDhBlockRatioAtlas(
                    atlas.handle.image, atlas.handle.view, atlas.handle.sampler,
                    atlas.handle.device);
        } catch (Throwable failure) {
            failures++;
            log("atlas 推送失败（native 调用异常）：" + failure);
            return;
        }

        published = accepted;
        publishedAtlasView = atlas.handle.view;
        pushes++;
        log("推送 atlas（仅 atlas）第 " + pushes + " 次：atlasView=0x"
                + Long.toHexString(atlas.handle.view)
                + " ⇒ C++ 判定可用=" + accepted + "（细节见 rt_debug.log 的 [DH TEX] 行）");
    }

    /** 换世界/卸载时清掉记账，下次进世界重新推送。 */
    static void reset() {
        published = false;
        publishedColorView = 0L;
        publishedDepthView = 0L;
        publishedAtlasView = 0L;
        lastMissReason = null;
        nextCheckFrame = 0L;
    }

    //=====================================================================//
    // 句柄抽取                                                            //
    //=====================================================================//

    /** 一次抽取的结果。都是原始 long —— 不持有任何 renderpearl 对象，避免跨帧留引用。 */
    private static final class Handle {
        final long image;
        final long view;
        final long sampler;
        final long device;
        final int width;
        final int height;

        Handle(long image, long view, long sampler, long device, int width, int height) {
            this.image = image;
            this.view = view;
            this.sampler = sampler;
            this.device = device;
            this.width = width;
            this.height = height;
        }
    }

    @FunctionalInterface
    private interface WrapperSupplier {
        Object get() throws Throwable;
    }

    /** 一次 {@link #fetch} 的结果：句柄为 null 时 {@link #reason} 一定非空。 */
    private static final class Fetch {
        final Handle handle;
        final String reason;

        Fetch(Handle handle, String reason) {
            this.handle = handle;
            this.reason = reason;
        }
    }

    private static Fetch fetch(String label, WrapperSupplier supplier) {
        Object payload;
        try {
            Object result = supplier.get();
            if (result == null) return new Fetch(null, "接口返回 null");
            // DhApiResult<IDhApiBlazeTextureWrapper>：success/message/payload 都是公开字段。
            Field success = cachedPublicField(result.getClass(), "success");
            Field payloadField = cachedPublicField(result.getClass(), "payload");
            Object ok = success.get(result);
            if (!Boolean.TRUE.equals(ok)) {
                // "纹理还没创建/绑定"是常态（DH 不自己渲染时它永远不建），
                // 但要把 DH 自己给的理由带出来，好和"反射失败"区分开。
                return new Fetch(null, "未就绪：" + readString(result, "message"));
            }
            payload = payloadField.get(result);
        } catch (Throwable failure) {
            return new Fetch(null, "取包装失败：" + failure);
        }
        return extractHandle(payload, label);
    }

    /**
     * 直取 DH 的 {@code blockRatioAtlas}：先主动把待上传的 tile 刷进图集，再读它的纹理包装。
     *
     * <p><b>为什么必须由我们刷</b>：{@code AbstractBlockTextureAtlas.uploadPendingTiles()}
     * 在 DH 里只被它自己的渲染器调用（全 jar 只有 {@code BlazeDhTerrainRenderer} 与
     * {@code GlDhMetaRenderer} 两处引用），而我们把 {@code rendererMode} 留在
     * {@code DISABLED} ⇒ 那个调用永不执行 ⇒ 图集永远停在"未创建"，binding 49 只能落回
     * 原版方块图集，远场顶点即便带了真 tileId 也全是错色。</p>
     *
     * <p><b>为什么必须绕开 API getter</b>：{@code DhApiRenderProxy} 里的
     * {@code activeBlazeDhBlockRatioAtlasTextureWrapper} 静态字段只由
     * {@code BlazeDhTerrainRenderer} 写（全 jar grep 只有它和 DhApiRenderProxy 自身两处），
     * 同一个原因 ⇒ 它恒为 null，{@code getDhBlockRatioAtlasTextureBlazeWrapper()}
     * 永远返回"还未创建"。所以这里直接走
     * {@code BlazeBlockTextureAtlas.INSTANCE.getTextureWrapper()}。</p>
     *
     * <p>反射而非直接引用：这个类在 DH 的内部包（不是 API 包），跨版本改名更频繁；
     * 反射失败只退化成"这次没拿到"，不影响游戏。本方法每 2 秒被调一次，
     * {@code uploadPendingTiles()} 在"没有新 tile"时是直接 return 的空操作。</p>
     */
    private static Fetch fetchAtlasDirect() {
        Object atlas;
        try {
            Class<?> atlasClass = cachedAtlasClass;
            if (atlasClass == null) {
                atlasClass = Class.forName(
                        "com.seibel.distanthorizons.common.render.blaze.wrappers.texture"
                                + ".BlazeBlockTextureAtlas");
                cachedAtlasClass = atlasClass;
            }
            atlas = cachedPublicField(atlasClass, "INSTANCE").get(null);
        } catch (Throwable failure) {
            return new Fetch(null, "取 BlazeBlockTextureAtlas.INSTANCE 失败：" + failure);
        }
        if (atlas == null) {
            return new Fetch(null, "BlazeBlockTextureAtlas.INSTANCE 为 null");
        }

        // 刷图集。首次调用会在这里建出 GPU 纹理（DH 自己渲染时由它的渲染器做），
        // 之后每次只上传新注册的 tile；尺寸变化时视图会换新，调用方据此重推句柄。
        try {
            cachedMethod(atlas.getClass(), "uploadPendingTiles").invoke(atlas);
        } catch (Throwable failure) {
            return new Fetch(null, "uploadPendingTiles() 失败：" + failure);
        }

        Object wrapper;
        try {
            wrapper = cachedMethod(atlas.getClass(), "getTextureWrapper").invoke(atlas);
        } catch (Throwable failure) {
            return new Fetch(null, "getTextureWrapper() 失败：" + failure);
        }
        if (wrapper == null) {
            return new Fetch(null, "getTextureWrapper() 返回 null");
        }
        return extractHandle(wrapper, "blockRatioAtlas(直取)");
    }

    /** 从 DH 的纹理包装里抽出几个裸 long。失败时 {@link Fetch#reason} 一定非空。 */
    private static Fetch extractHandle(Object wrapperObject, String label) {
        if (!(wrapperObject instanceof IDhApiBlazeTextureWrapper wrapper)) {
            return new Fetch(null, "包装不是 IDhApiBlazeTextureWrapper："
                    + (wrapperObject == null ? "null" : wrapperObject.getClass().getName()));
        }

        long view = 0L;
        long sampler = 0L;
        long image = 0L;
        long device = 0L;
        int width = 0;
        int height = 0;

        // 主路径：wrapper 自己的字段（BlazeTextureWrapper.texture / .textureView / .textureSampler）。
        Object texture = readField(wrapper, "texture");
        Object textureView = readField(wrapper, "textureView");
        Object textureSampler = readField(wrapper, "textureSampler");

        // 兜底：wrapped 是 Object[3]{texture, view, sampler}，按类名归位。
        Object wrapped = safeWrapped(wrapper);
        if (wrapped != null && wrapped.getClass().isArray()) {
            int length = Array.getLength(wrapped);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(wrapped, i);
                if (element == null) continue;
                String name = element.getClass().getSimpleName();
                if (textureView == null && name.contains("TextureView")) textureView = element;
                else if (textureSampler == null && name.contains("Sampler")) textureSampler = element;
                else if (texture == null && name.contains("Texture")) texture = element;
            }
        }

        if (textureView != null) view = readLong(textureView, "vkImageView");
        if (textureSampler != null) sampler = readLong(textureSampler, "vkSampler");
        if (texture != null) {
            image = readLong(texture, "vkImage");
            // renderpearl 的 VulkanDevice.vkDevice 是 LWJGL 的 VkDevice 对象，
            // 所以这里要两步：先取到设备对象，再取它的裸指针。
            Object deviceObject = readField(texture, "device");
            if (deviceObject != null) device = readLong(deviceObject, "vkDevice");
        }
        try {
            width = wrapper.getWidth();
            height = wrapper.getHeight();
        } catch (Throwable ignored) {
            // 尺寸拿不到不影响句柄本身的使用。
        }

        if (view == 0L) {
            return new Fetch(null, "vkImageView=0（textureView="
                    + (textureView == null ? "null" : textureView.getClass().getName())
                    + " sampler=" + (textureSampler == null ? "null" : textureSampler.getClass().getName()) + "）");
        }
        if (device == 0L) {
            // 设备指针解析失败时，"不同源"的结论不可信，必须显式说明，否则会误导排查方向。
            log("[" + label + "] 警告：VkDevice 指针未解析成功（device=0）⇒"
                    + " C++ 侧会报「设备尚未打入」或「不同源」，那个结论此时无效。");
        }
        return new Fetch(new Handle(image, view, sampler, device, width, height), null);
    }

    private static Object safeWrapped(IDhApiBlazeTextureWrapper wrapper) {
        try {
            return wrapper.getWrappedMcObject();
        } catch (Throwable failure) {
            return null;
        }
    }

    /** 读一个公开 String 字段；拿不到就返回一句占位（用于 {@code DhApiResult.message}）。 */
    private static String readString(Object target, String name) {
        try {
            Field field = cachedPublicField(target.getClass(), name);
            Object value = field.get(target);
            return value == null ? "（无 message）" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "（无 message 字段）";
        }
    }

    private static Object readField(Object target, String name) {
        try {
            // 缓存后 getDeclaredField 的 NoSuchFieldException 只在首次解析发生；
            // 查找语义与之前完全一致（仍只查本类声明字段，不上溯父类）。
            Field field = cachedDeclaredField(target.getClass(), name);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long readLong(Object target, String name) {
        return toPointer(readField(target, name));
    }

    /**
     * 把字段值转成裸指针（{@code long}）。
     *
     * <p>两条路径都要走：{@code vkImage / vkImageView / vkSampler} 是 <b>primitive long</b>
     * 字段（反射后就是 {@link Long}），但 {@code vkDevice} 是 <b>LWJGL 的 {@code VkDevice} 对象</b>
     * ——它不是 {@link Number}，只认 Number 会静默拿到 0，然后"设备不同源"的错误结论就被
     * 当成真结论了。LWJGL 的句柄类都提供 {@code address()}，这条路径必须留着。</p>
     */
    private static long toPointer(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        try {
            Method address = cachedMethod(value.getClass(), "address");
            Object result = address.invoke(value);
            if (result instanceof Number number) return number.longValue();
        } catch (Throwable ignored) {
            // 不是 LWJGL 句柄也不是数字 ⇒ 拿不到指针，返回 0（调用方会据此报"未解析"）。
        }
        return 0L;
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
