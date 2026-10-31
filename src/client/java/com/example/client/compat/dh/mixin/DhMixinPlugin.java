package com.example.client.compat.dh.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * DH 混入配置的<b>软依赖门</b>。
 *
 * <h2>为什么必须有它</h2>
 * <p>{@code fabric.mod.json} 里 DH 只是 {@code suggests}（可选）。若无门控，
 * 未装 DH 的玩家会在类加载阶段碰到 {@code LodBufferContainer} 不存在 ⇒ 混入应用失败。
 * 本插件让整套 {@code orion.dh.mixins.json} 在无 DH 时<b>整体不生效</b>，
 * 连混入类本身都不会被加载。</p>
 *
 * <h2>注意事项（来自 {@link IMixinConfigPlugin} 的 javadoc）</h2>
 * <p>插件方法在类转换早期被调用，因此<b>本类只允许引用 FabricLoader 这类引导期可用的类</b>，
 * 绝不能引用 Minecraft 或 DH 自己的类型。</p>
 */
public final class DhMixinPlugin implements IMixinConfigPlugin {
    private static final String DH_MOD_ID = "distanthorizons";

    private static boolean present() {
        return FabricLoader.getInstance().isModLoaded(DH_MOD_ID);
    }

    @Override
    public void onLoad(String mixinPackage) {
        System.err.println("[DH-MIXIN] 配置加载 " + mixinPackage
                + "；Distant Horizons " + (present() ? "在场 ⇒ 混入生效" : "缺席 ⇒ 整套跳过"));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return present();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
