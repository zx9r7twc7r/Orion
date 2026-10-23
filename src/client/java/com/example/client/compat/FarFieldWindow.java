package com.example.client.compat;

import net.minecraft.client.Minecraft;

/**
 * 远场窗口的<b>单一真源</b>：近场保留半径（seam）、远场内洞半径、LOD 环对齐全部从这里出。
 *
 * <p><b>为什么必须只有一个出处</b>（09-25 审计）：seam 与内洞历史上各自推导过一遍，
 * 只有在 {@code rd % 4 == 0} 时它们才恰好相等。rd=13 时 seam = {@code ceil(13/4)*64}
 * = 256 格，而内洞 = {@code rd*16} = 208 格 ⇒ 中间 48 格「原版只加载到 208 格（够不到）、
 * 我方远场又从 208 格起挖洞（不画）」⇒ 一圈<b>谁都不绘制的真空带</b>，实机表现就是
 * 用户报的"方形缺口 / 立在远处的一堵墙"。两边都改读本类后，这个差值在数学上消失。</p>
 *
 * <p><b>09-25 二次修正（环数必须 floor）</b>：即便只剩一个出处，环数也必须是
 * {@code floor(rd/4)} 而不是 {@code ceil(rd/4)}。原版真正加载到的距离是
 * {@code rd*16} 格；ceil 在 {@code rd % 4 != 0} 时会推出一个超过它的缝
 * （rd=5 → 缝 128 格，原版只到 80 格），那段距离原版没有数据可画、LOD 又被 ring
 * 过滤掉，仍然是谁都不画的真空带。floor 满足 {@code floor(rd/4)*64 <= rd*16}
 * 恒成立，缝永不超出原版可达范围。</p>
 *
 * <p>本类只依赖原版类型，不 import 任何 {@code com.seibel.distanthorizons.*}，
 * 因此 {@code FarFieldGate}（compat 包）与 {@code DhFarFieldUploader}（compat.dh 包）
 * 可以安全共用。</p>
 */
public final class FarFieldWindow {
    /** LOD1 section 宽（格）。近场窗口与 LOD ring 边界都对齐这个网格。 */
    public static final int LOD_SECTION_BLOCKS = 64;

    private FarFieldWindow() {
    }

    /** 原版视距（区块），夹到 {@code >= 2}（rd<2 时缝环无法对齐）。 */
    public static int renderDistanceChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) return 12;
        return Math.max(2, minecraft.options.renderDistance().get());
    }

    /**
     * 缝环（64 格 LOD section 数）：ring &lt; 本值的 LOD 与近场重叠，过滤。
     *
     * <p>用 floor 而非 ceil，理由见类注释：ceil 会在 {@code rd % 4 != 0} 时推出一个
     * 原版够不到的缝（rd=5 → 128 格 vs 原版 80 格），留下真空带。rd &lt; 4 时仍夹到 1，
     * 因为 LOD 金字塔没有比 64 格更细的 ring。</p>
     */
    public static int lodRingSections() {
        return Math.max(1, renderDistanceChunks() / 4);
    }

    /**
     * 近场保留半径（16 格 section 数）= <b>远场内洞半径</b>。
     *
     * <p>不变量：{@code FarFieldGate.seamSections() == DhFarFieldUploader 的内洞区块数}。
     * 任何一侧再自己推导一遍都会重新长出真空带。</p>
     */
    public static int seamSections() {
        return lodRingSections() * (LOD_SECTION_BLOCKS / 16);
    }

    /** 近场保留半径（格）= 远场内洞半径（格）。 */
    public static int seamBlocks() {
        return seamSections() * 16;
    }
}