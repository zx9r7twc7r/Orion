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
 *       注意：**不需要手部几何** —— 光源只是一条位置/强度/颜色记录。
 *       2026-10-25 修正：第一人称手部几何其实**一直在被 Java 侧捕获**（EntityRtCapture
 *       的 model/item 两条链），此前完全不参与光追的真实原因是帧窗口在手提交之前就
 *       {@code endEntityFrame()} 了，上传被原生门禁静默丢弃（见
 *       {@code EntityRtCapture.finishEntityFrame}）。与 {@code beginHandPhase()}
 *       无关 —— 那个方法始终没有调用者，是死代码。</li>
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
    private static final int MAX_LIGHTS = 32;

    private static final int BYTES_PER_LIGHT = 32;

    private static final double MAX_DISTANCE = 48.0;

    

    private static final boolean ENABLED =
            !"0".equals(System.getProperty("orion.dynamicLights"));

    

    private static final Map<String, int[]> ENTITY_LIGHTS = Map.ofEntries(
            Map.entry("blaze", new int[] {15, rgb(255, 140, 46)}),
            Map.entry("magma_cube", new int[] {12, rgb(255, 89, 20)}),
            Map.entry("small_fireball", new int[] {14, rgb(255, 128, 31)}),
            Map.entry("fireball", new int[] {15, rgb(255, 128, 31)}),
            Map.entry("dragon_fireball", new int[] {15, rgb(255, 115, 160)}),
            Map.entry("glow_squid", new int[] {12, rgb(56, 199, 255)}),
            Map.entry("allay", new int[] {10, rgb(140, 217, 255)}),
            Map.entry("spectral_arrow", new int[] {6, rgb(128, 230, 255)}),
            Map.entry("end_crystal", new int[] {15, rgb(255, 140, 242)}),
            Map.entry("lightning_bolt", new int[] {15, rgb(217, 230, 255)}),
            Map.entry("firework_rocket", new int[] {10, rgb(255, 217, 128)}),
            Map.entry("glow_item_frame", new int[] {8, rgb(255, 209, 122)})
    );

    private static final Map<String, Integer> ITEM_LIGHT_EMISSION = Map.of(
            "lava_bucket", 15
    );

    private static final ByteBuffer records =
            ByteBuffer.allocateDirect(MAX_LIGHTS * BYTES_PER_LIGHT)
                    .order(ByteOrder.nativeOrder());

    private static final List<Candidate> candidates = new ArrayList<>(MAX_LIGHTS * 2);

    private static int lastReportedCount = -1;

    

    private static boolean nativeUnavailable = false;

    private DynamicLightCapture() {}

    private static final class Candidate
    {
        int id;
        double x;
        double y;
        double z;
        int emission;
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
                    .putInt(0)
                    .putInt(candidate.packedRgb)
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

        candidates.sort(Comparator.comparingInt(candidate -> candidate.id));

        return Math.min(candidates.size(), MAX_LIGHTS);
    }

    private static void collectHeldLights(Minecraft minecraft)
    {
        final int playerId = minecraft.player.getId();

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
            return rgb(255, 140, 46);
        }

        final Block block = blockItem.getBlock();

        if(block == Blocks.TORCH || block == Blocks.WALL_TORCH ||
                block == Blocks.LANTERN || block == Blocks.CAMPFIRE ||
                block == Blocks.FIRE)
        {
            return rgb(255, 140, 51);
        }
        if(block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH ||
                block == Blocks.SOUL_LANTERN || block == Blocks.SOUL_CAMPFIRE ||
                block == Blocks.SOUL_FIRE)
        {
            return rgb(51, 191, 255);
        }
        if(block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH)
        {
            return rgb(255, 31, 15);
        }
        if(block == Blocks.LAVA)
        {
            return rgb(255, 69, 13);
        }
        return rgb(255, 209, 122);
    }

    private static int rgb(int red, int green, int blue)
    {
        return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    private static String pathOf(Object registryEntry)
    {
        final var key = registryEntry instanceof Item item
                ? BuiltInRegistries.ITEM.getKey(item)
                : BuiltInRegistries.ENTITY_TYPE.getKey(
                        (net.minecraft.world.entity.EntityType<?>) registryEntry);

        return key == null ? "" : key.getPath();
    }
}
