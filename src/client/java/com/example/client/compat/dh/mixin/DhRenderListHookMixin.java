package com.example.client.compat.dh.mixin;

import com.example.client.compat.dh.DhLodMeshSink;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * 接管点：每 tick 把 DH <b>自己算好的"该画哪些 section"</b>清单抄给 {@link DhLodMeshSink}。
 *
 * <h2>为什么必须是这里，而不是别的</h2>
 * <p>DH 的 {@code LodQuadTree} 里维护一个私有字段 {@code enabledSections}，
 * 由 {@code tryTick(DhBlockPos2D)} → {@code updateAllRenderSections(pos)} 每 tick 重算：
 * 它按「该距离该用的 detail level」在四叉树里<b>每柱只启用一个节点</b>，天然满足
 * "粗细不重叠、子节点缺席处用父节点兜底"。这就是远场该画什么的<b>唯一真源</b>。</p>
 *
 * <p>⚠ 曾打算挂在 {@code populateListWithEnabledRenderSections(ArrayList)} 上——那是错的：
 * 整个 DH 里只有 {@code RenderBufferHandler.buildRenderList} 会调它，而
 * {@code ClientApi.renderLodLayer} 在 {@code rendererMode == DISABLED} 时<b>提前 return</b>，
 * 根本走不到 {@code buildRenderList}。我们接管几何时正是把 DH 自己的绘制关掉的
 * （{@code -Dorion.dh.disableRendering=true}）⇒ 那个注入点永远不会触发。</p>
 *
 * <p>{@code tryTick} 则相反：它由 {@code ClientLevelModule.clientTick()} 每 tick
 * <b>无条件</b>调用（只看"玩家是否存在"），与 {@code rendererMode} 无关。</p>
 *
 * <h2>读法</h2>
 * <p>直接 {@link Shadow} 那个 public 方法把它拷进我们的列表：该方法内部自己加
 * {@code enabledRenderSectionLock} 锁、自己清空并拷 {@code enabledSections}
 * （{@code javap -c} 钉死：只有 clear + size/get/add + unlock，无任何筛选开销），
 * 所以不需要去碰私有字段，也不需要考虑加锁。</p>
 *
 * <h2>只读不改写</h2>
 * <p>{@code @At("RETURN")}、不 cancel、不 setReturnValue。任何异常都在这里吞掉，
 * 绝不能影响 DH。</p>
 */
@Mixin(LodQuadTree.class)
public abstract class DhRenderListHookMixin {

    /** {@code LodQuadTree} 自带的"把当前启用的 section 拷进参数列表"的公开方法。 */
    @Shadow
    public abstract void populateListWithEnabledRenderSections(ArrayList<LodRenderSection> list);

    @Inject(method = "tryTick", at = @At("RETURN"))
    private void orion$publishEnabledSections(DhBlockPos2D center, CallbackInfo ci) {
        // 本混入对每个装了 DH 的玩家都会加载，所以先问一句要不要抄：关掉接管链时
        // 每 tick 白拷一份上千长度的清单是纯浪费。
        if (!DhLodMeshSink.wantsEnabledSections()) return;
        try {
            final ArrayList<LodRenderSection> sections = new ArrayList<>();
            populateListWithEnabledRenderSections(sections);
            final int size = sections.size();
            final long[] positions = new long[size];
            for (int i = 0; i < size; i++) {
                positions[i] = sections.get(i).pos;
            }
            DhLodMeshSink.onEnabledSections(positions);
        } catch (Throwable ignored) {
            // 抄清单绝不允许影响 DH 的 tick。
        }
    }
}
