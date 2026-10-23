package com.example.client;

import com.example.client.config.RTXSettingsConfig;
import com.example.vulkan.NativeVulkan;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * 2026-10-07 动态光源（移动光源）采集。
 *
 * <p>两类来源，都是原版没有"实体会发光"这个概念、只能推出来的：</p>
 * <ul>
 *   <li><b>第一人称手持发光物</b>：读玩家主手/副手的 {@link ItemStack}，方块物品直接问
 *       {@link BlockState#getLightEmission()}（火把 14、灯笼 15、萤石 15…），非方块物品
 *       查一张小表（岩浆桶等）。光源放在"手"的大致位置（眼位 + 视线方向 0.35 格 - 0.25 格）。
 *       注意：**不需要手部几何** —— 光源只是一条位置/强度/颜色记录，而第一人称手部的几何
 *       捕获链路在本工程里其实是断的（{@code beginHandPhase()} 无调用者）。</li>
 *   <li><b>自带发光的实体</b>：见 {@link #ENTITY_LIGHTS}。</li>
 * </ul>
 *
 * <p>每帧在渲染线程调用一次 {@link #submitFrame()}，把 ≤{@link #MAX_LIGHTS} 条记录推给
 * native（{@code binding 61}）。native 侧内容是逐字节比较的：站着不动举火把时一次
 * memcpy 比较就返回，整段上传跳过。</p>
 *
 * <p><b>颜色（2026-10-07 起）</b>：每条记录带**任意 RGB**（打包进 {@code meta.y} 的
 * 8/8/8），着色器优先用它；只有当 RGB 为 0（没给颜色）时才回退到 {@code vliDecodeEmitterColor}
 * 的调色板。记录里还剩两个空 uint 没用。</p>
 *
 * <p><b>槽位稳定性</b>：记录按"实体 id 升序"排列后再上报 —— ReSTIR reservoir 里存的是
 * 光源索引，顺序不稳会每帧击穿时域历史。这与 native 侧局部光表按 Section key 排序是
 * 同一个理由。</p>
 */
public final class DynamicLightCapture
{
    /** 每帧最多上报多少条。native 侧上限 128，这里留足余量即可。 */
    private static final int MAX_LIGHTS = 32;

    /** 与 native 侧 {@code DynamicLightRecord} 一致：4 float + 4 int。 */
    private static final int BYTES_PER_LIGHT = 32;

    /** 超过这个距离的实体不参与（本地光本来就有距离衰减，远处不值得付采样成本）。 */
    private static final double MAX_DISTANCE = 48.0;

    /**
     * 应急总闸（不需要重编、不需要 GUI）：{@code -Dorion.dynamicLights=0} 关掉整条链路
     * —— 连每帧采集与 JNI 调用一起省掉，比把设置里的强度滑条拖到 0 更彻底。
     */
    private static final boolean ENABLED =
            !"0".equals(System.getProperty("orion.dynamicLights"));

    /**
     * 发光实体 → {发射等级 0..15, RGB}。
     *
     * <p>只收"看起来就在发光"的实体。刻意**不含** {@code experience_orb} 这类成百上千的量：
     * 它们会瞬间占满 32 个槽位、把烈焰人这类真正的光源挤掉（而且经验塔会整片发亮）。</p>
     */
    private static final Map<String, int[]> ENTITY_LIGHTS = Map.ofEntries(
            // 暖橙
            Map.entry("blaze", new int[] {15, rgb(255, 140, 46)}),
            Map.entry("magma_cube", new int[] {12, rgb(255, 89, 20)}),
            Map.entry("small_fireball", new int[] {14, rgb(255, 128, 31)}),
            Map.entry("fireball", new int[] {15, rgb(255, 128, 31)}),
            Map.entry("dragon_fireball", new int[] {15, rgb(255, 115, 160)}),
            // 青 / 蓝白
            Map.entry("glow_squid", new int[] {12, rgb(56, 199, 255)}),
            Map.entry("allay", new int[] {10, rgb(140, 217, 255)}),
            Map.entry("spectral_arrow", new int[] {6, rgb(128, 230, 255)}),
            // 高亮
            Map.entry("end_crystal", new int[] {15, rgb(255, 140, 242)}),
            Map.entry("lightning_bolt", new int[] {15, rgb(217, 230, 255)}),
            Map.entry("firework_rocket", new int[] {10, rgb(255, 217, 128)}),
            Map.entry("glow_item_frame", new int[] {8, rgb(255, 209, 122)})
    );

    /** 非方块物品的发光（方块物品走 BlockState.getLightEmission）。 */
    private static final Map<String, Integer> ITEM_LIGHT_EMISSION = Map.of(
            "lava_bucket", 15
    );

    private static final ByteBuffer records =
            ByteBuffer.allocateDirect(MAX_LIGHTS * BYTES_PER_LIGHT)
                    .order(ByteOrder.nativeOrder());

    private static final List<Candidate> candidates = new ArrayList<>(MAX_LIGHTS * 2);

    private static int lastReportedCount = -1;

    /**
     * native 侧缺这个符号时置位（例如把 {@code run/rtx_backend.dll} 回滚成旧版）。
     * 不这样处理的话，每帧一次 {@code UnsatisfiedLinkError} 会把渲染线程打断。
     */
    private static boolean nativeUnavailable = false;

    private DynamicLightCapture() {}

    private static final class Candidate
    {
        int id;
        double x;
        double y;
        double z;
        int emission;
        /** 颜色（8/8/8 打包）。0 = 没给 ⇒ 着色器回退到调色板。 */
        int packedRgb;
    }

    /**
     * 强度旋钮：0 = 关掉移动光源（会连同可见性射线一起省掉）。
     *
     * <p>真源是 {@link RTXSettingsConfig} 的「移动光源强度」滑条（0..4）。每帧读一次
     * —— 这个值不经过 native setter，而是随 {@code uploadDynamicLights} 的第三个参数
     * 一起下发（强度落在动态光源那条 SSBO 的头部）。</p>
     */
    private static float intensity()
    {
        return RTXSettingsConfig.getDynamicLightIntensity();
    }

    /** 渲染线程、每帧一次（由 {@code LevelRendererMixin} 的帧首调用）。 */
    public static void submitFrame()
    {
        if(!ENABLED || nativeUnavailable || NativeVulkan.isLoaded() == false)
        {
            return;
        }

        final int count = collect();

        records.clear();

        for(int index = 0; index < count; ++index)
        {
            final Candidate candidate = candidates.get(index);

            records
                    .putFloat((float) candidate.x)
                    .putFloat((float) candidate.y)
                    .putFloat((float) candidate.z)
                    .putFloat(candidate.emission)
                    .putInt(0)                  // meta.x：typeCode —— 现在只做兜底，0 = 用 RGB
                    .putInt(candidate.packedRgb) // meta.y：8/8/8 RGB
                    .putInt(0)
                    .putInt(0);
        }

        records.flip();

        try
        {
            NativeVulkan.uploadDynamicLights(
                    MemoryUtil.memAddress(records),
                    count,
                    intensity()
            );
        }
        catch(Throwable failure)
        {
            // 旧 DLL 没有这个符号：一次性降级并留一行日志，不要每帧抛异常。
            nativeUnavailable = true;
            System.out.println("[DYNLIGHT] disabled: " + failure);
            return;
        }

        if(count != lastReportedCount)
        {
            lastReportedCount = count;
            System.out.println("[DYNLIGHT] moving lights = " + count);
        }
    }

    /** 采集并排序，返回条数（candidates 的前 count 个即有效记录）。 */
    private static int collect()
    {
        candidates.clear();

        final Minecraft minecraft = Minecraft.getInstance();

        if(minecraft.level == null || minecraft.player == null)
        {
            return 0;
        }

        collectHeldLights(minecraft);
        collectEntityLights(minecraft);

        // 按实体 id 升序 —— 槽位稳定（见类注释）。
        candidates.sort(Comparator.comparingInt(candidate -> candidate.id));

        return Math.min(candidates.size(), MAX_LIGHTS);
    }

    private static void collectHeldLights(Minecraft minecraft)
    {
        final int playerId = minecraft.player.getId();

        // 眼位 + 视线方向偏移 ≈ 手上的位置。两种手分别算，避免主副手都有光时两点重合。
        addHeldLight(
                minecraft,
                minecraft.player.getMainHandItem(),
                playerId,
                true
        );
        addHeldLight(
                minecraft,
                minecraft.player.getOffhandItem(),
                playerId,
                false
        );
    }

    private static void addHeldLight(
            Minecraft minecraft,
            ItemStack stack,
            int playerId,
            boolean mainHand)
    {
        final int emission = emissionOfStack(stack);

        if(emission <= 0)
        {
            return;
        }

        final var eye = minecraft.player.getEyePosition(1.0F);
        final var look = minecraft.player.getLookAngle();
        final double side = mainHand ? 0.35 : -0.35;

        final Candidate candidate = new Candidate();
        candidate.id = playerId;
        candidate.x = eye.x + look.x * 0.35 + look.z * side * 0.4;
        candidate.y = eye.y + look.y * 0.35 - 0.25;
        candidate.z = eye.z + look.z * 0.35 - look.x * side * 0.4;
        candidate.emission = emission;
        candidate.packedRgb = heldLightColor(stack);

        candidates.add(candidate);
    }

    private static void collectEntityLights(Minecraft minecraft)
    {
        final double playerX = minecraft.player.getX();
        final double playerY = minecraft.player.getY();
        final double playerZ = minecraft.player.getZ();

        for(Entity entity : minecraft.level.entitiesForRendering())
        {
            if(entity == null || entity == minecraft.player)
            {
                continue;
            }

            final int[] light = ENTITY_LIGHTS.get(pathOf(entity.getType()));

            if(light == null)
            {
                continue;
            }

            final double dx = entity.getX() - playerX;
            final double dy = entity.getY() - playerY;
            final double dz = entity.getZ() - playerZ;

            if(dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE)
            {
                continue;
            }

            final Candidate candidate = new Candidate();
            candidate.id = entity.getId();
            candidate.x = entity.getX();
            candidate.y = entity.getY() + entity.getBbHeight() * 0.5;
            candidate.z = entity.getZ();
            candidate.emission = light[0];
            candidate.packedRgb = light[1];

            candidates.add(candidate);
        }
    }

    private static int emissionOfStack(ItemStack stack)
    {
        if(stack == null || stack.isEmpty())
        {
            return 0;
        }

        final Item item = stack.getItem();

        // 方块物品：直接问方块自己的光照等级（火把/灯笼/萤石/海晶灯/菌光体…全都覆盖）。
        if(item instanceof BlockItem blockItem)
        {
            return blockItem.getBlock().defaultBlockState().getLightEmission();
        }

        return ITEM_LIGHT_EMISSION.getOrDefault(pathOf(item), 0);
    }

    /**
     * 手持发光物的颜色。
     *
     * <p>与调色板（{@code vliDecodeEmitterColor}）**取同样的值**，这样"给了 RGB"与
     * "只给 typeCode"两条路在火把上观感一致；好处是从此可以在这里按资源包/偏好单独改颜色，
     * 不必动着色器。</p>
     */
    private static int heldLightColor(ItemStack stack)
    {
        final Item item = stack.getItem();

        if(!(item instanceof BlockItem blockItem))
        {
            // 非方块物品（岩浆桶一类）统一暖橙。
            return rgb(255, 140, 46);
        }

        final Block block = blockItem.getBlock();

        if(block == Blocks.TORCH || block == Blocks.WALL_TORCH ||
                block == Blocks.LANTERN || block == Blocks.CAMPFIRE ||
                block == Blocks.FIRE)
        {
            return rgb(255, 140, 51);            // 暖橙（调色板 1）
        }

        if(block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH ||
                block == Blocks.SOUL_LANTERN || block == Blocks.SOUL_CAMPFIRE ||
                block == Blocks.SOUL_FIRE)
        {
            return rgb(51, 191, 255);            // 青蓝（调色板 2）
        }

        if(block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH)
        {
            return rgb(255, 31, 15);             // 红（调色板 3）
        }

        if(block == Blocks.LAVA)
        {
            return rgb(255, 69, 13);             // 岩浆橙（调色板 4）
        }

        // 暖白（调色板默认值）：萤石 / 海晶灯 / 菌光体 / 南瓜灯 / 红石灯…
        return rgb(255, 209, 122);
    }

    private static int rgb(int red, int green, int blue)
    {
        return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    private static String pathOf(Object registryEntry)
    {
        // 1.21+ 的注册表键是 Identifier；这里只取 path（"torch" / "blaze" …）。
        final var key = registryEntry instanceof Item item
                ? BuiltInRegistries.ITEM.getKey(item)
                : BuiltInRegistries.ENTITY_TYPE.getKey(
                        (net.minecraft.world.entity.EntityType<?>) registryEntry);

        return key == null ? "" : key.getPath();
    }
}
