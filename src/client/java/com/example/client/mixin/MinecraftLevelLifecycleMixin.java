package com.example.client.mixin;

import com.example.client.compat.FarFieldGate;
import com.example.client.compat.dh.DhBridge;
import com.example.vulkan.NativeVulkan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 换世界时释放上一个存档留在原生侧的光追几何。
 *
 * <p>原生侧的 {@code g_chunkMeshes} 是 RT 几何的唯一权威来源：每个条目都会
 * 得到一个 Section BLAS 和一条 TLAS 实例，所以残留条目会同时出现在画面和
 * 光线求交里 —— 表现为"上一个存档的地形投影"：碰不到（原版碰撞与大世界状态
 * 早已切走）、却又参与光追。</p>
 *
 * <p>清除必须挂在 {@code Minecraft.setLevel} 的 HEAD，而不是渲染路径上：
 * 这个入口只在 ClientPacketListener 的登录 / 重生里被调用，此刻新世界的
 * ClientLevel 刚构造完、还没有任何 Section 进入编译，所以清掉的只可能是旧
 * 世界的残留。若改在 GameRenderer.extract 里按 ready 翻转检测，新世界已经
 * 编译并上传的 Section 会被一起抹掉，而原版不会再把它们标脏重编译，留下
 * 永久空洞。</p>
 *
 * <p>Section 的上传点只有 SectionCompiler.compile 一处，换世界后每个 Section
 * 都会重新编译上传，因此清空不需要任何配套的重建请求。</p>
 */
@Mixin(Minecraft.class)
public class MinecraftLevelLifecycleMixin {

    @Inject(
            method = "setLevel",
            at = @At("HEAD")
    )
    private void rtx$releasePreviousWorldGeometry(
            ClientLevel newLevel,
            CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        // 同一等级被重复设置时不必触发一次全量重建。
        if (minecraft.level == newLevel) {
            return;
        }

        if (!NativeVulkan.isLoaded()) {
            return;
        }

        NativeVulkan.clearWorldGeometry();

        // DH 远场网格也住在 g_chunkMeshes 里，已被上面一并清掉，但 Java 侧的
        // 上传指纹仍认为它在线：同一原点重进世界时指纹命中就不会再上传，远场
        // 会静默缺失直到玩家移动。重置两侧状态，让远场按新世界的快照重铺。
        DhBridge.reset();

        // 2026-10-09 修「依次进多个存档后越来越卡」：FarFieldGate 的覆盖表
        // （section 坐标 → 接管状态）**从不清**，表内容于是变成"所有进过的存档的
        // section 并集"，而它每帧都要整表遍历一次 ⇒ 每帧成本随存档数线性上涨。
        // 这里显式清一次（不依赖 DhBridge/Voxy 那两条路是否走到），清掉是安全的：
        // 上面刚把 native 几何全清，覆盖表描述的正是那批几何的接管状态。
        // 打印的那行就是"变卡"的量化指标：换世界后应归零。
        FarFieldGate.onLodHardReset();
    }
}
