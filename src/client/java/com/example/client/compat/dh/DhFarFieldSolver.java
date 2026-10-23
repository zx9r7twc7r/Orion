package com.example.client.compat.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

import java.util.Locale;

/**
 * DH 远场矩阵的<b>在线验证器</b>（2026-09-23 重写）。
 *
 * <h2>它不再做什么</h2>
 * <p>上一版这里试图"解算并补偿一个世界空间平移"，还把补偿乘回矩阵。那条路已经废掉：
 * 远场几何改成<b>锚定在我们自己的主射线</b>上之后（位置 = 相机 + 射线 × 距离，矩阵
 * 只负责给一个标量距离），就不存在"需要把点搬回射线上"这个需求了。改矩阵只会让
 * "矩阵到底对不对"更难判读。</p>
 *
 * <h2>它现在做什么</h2>
 * <ol>
 *   <li><b>填充顺序</b>（决定性，靠结构事实而不是猜）：透视投影矩阵在 reverse-Z 下
 *       {@code w_clip = -z_eye} ⇒ 数学上的<b>第 4 列</b>恒为 {@code (0,0,-1,0)}。于是
 *       <ul>
 *         <li>{@code arr[12..15] ≈ (0,0,-1,0)} ⇒ 数组按<b>列优先</b>落位 ⇒ GLSL 的
 *             {@code mat4} 直接可用 ⇒ <b>变体位 0（不转置）</b>；</li>
 *         <li>{@code arr[3],[7],[11],[15] ≈ (0,0,-1,0)} ⇒ 按行优先落位 ⇒ 需要转置
 *             ⇒ 变体位 2。</li>
 *       </ul>
 *       ⚠ <b>不要用 {@code dhModelViewMatrix} 判</b>：DH 给的那份平移是 0（相机在
 *       原点），两边分数会同时为 0 ⇒ 判据不适用。2026-09-23 实测正是
 *       {@code rowScore=colScore=0.0000}，旧版因此回落到"列序"却报出<b>错误</b>的
 *       变体位，并用错的读法去算视锥 ⇒ 自证"差异 88°"。改用投影矩阵后决定性。</li>
 *   <li><b>视锥一致性</b>：同一个 ndc 下 DH 与 MC 的射线方向夹角，<b>4 个变体全试</b>、
 *       取夹角最小的那个 —— 与 rgen 的自检同构（那边取"重建点落在本像素主射线上"
 *       的变体），两边独立得到同一个变体号才算真对了。判据用 {@code |dot|}：
 *       z 的正/反会让整体方向反向，但"是不是同一条直线"不变。</li>
 *   <li><b>有限性</b>：DH 每帧首个回调给的矩阵实测先是 NaN、后是量级荒谬的有限值，
 *       这里只做报告；推送侧（{@link DhFarFieldGeometry}）据此丢帧。</li>
 * </ol>
 *
 * <p>结论只进日志（{@code run/logs/latest.log}，TAG {@code [DH-MAT]}），不改矩阵、
 * 不替 rgen 决定变体 —— rgen 那边有独立的、平移无关的自检，两边互为验证。</p>
 */
final class DhFarFieldSolver {
    private static final String TAG = "[DH-MAT]";

    /** 探针：NDC（y 向上）。铺开在中心、四象限与近边缘，覆盖整个视锥。 */
    private static final float[][] PROBES = {
            {0.0f, 0.0f},
            {0.55f, 0.55f},
            {-0.55f, 0.55f},
            {0.55f, -0.55f},
            {-0.55f, -0.55f},
            {0.88f, 0.0f},
            {0.0f, 0.88f},
    };

    /** 结构判据两侧的分数差阈值：真正含那一列/行的一侧通常差 1 附近，0.01 足够分开。 */
    private static final float STRUCTURE_TOLERANCE = 0.01f;

    private static final float[] DH_INV = new float[16];
    private static final float[] DH_MV = new float[16];
    private static final float[] DH_PROJ = new float[16];
    private static final float[] MC_PROJ = new float[16];
    private static final float[] MC_INV = new float[16];

    private static final float[] POINT_A = new float[3];
    private static final float[] POINT_B = new float[3];
    private static final float[] DIR_DH = new float[3];
    private static final float[] DIR_MC = new float[3];

    private static long solveCount;

    private DhFarFieldSolver() {
    }

    /** 每帧调用一次；只读矩阵并（按节流）打日志。 */
    static void inspect(DhApiRenderParam param) {
        if (param == null) return;

        solveCount++;
        if (solveCount > 3 && solveCount % 1200 != 0) {
            return;
        }

        DhApiMat4f inverse = param.dhInverseMvmProjectionMatrix;
        DhApiMat4f modelView = param.dhModelViewMatrix;

        if (inverse == null || modelView == null) {
            log("=== 验证 #" + solveCount + "：矩阵字段缺失（inverse="
                    + (inverse != null) + " modelView=" + (modelView != null) + "）===");
            return;
        }

        inverse.putValuesInArray(DH_INV);
        modelView.putValuesInArray(DH_MV);
        safePut(param.dhProjectionMatrix, DH_PROJ);
        safePut(param.mcProjectionMatrix, MC_PROJ);
        safePut(param.mcInverseMvmProjectionMatrix, MC_INV);

        log("=== 验证 #" + solveCount + " worldYOffset=" + param.worldYOffset
                + " dhNear=" + fmt(param.nearClipPlane)
                + " dhFar=" + fmt(param.farClipPlane)
                + " partialTicks=" + fmt(param.partialTicks) + " ===");

        // ---- 1) 填充顺序：用投影矩阵的结构事实（决定性）-----------------------
        // 透视投影（reverse-Z，p_clip = P · p_eye）的数学第 4 列恒为 (0,0,-1,0)：
        // w_clip = -z_eye。所以"哪一组元素长得像 (0,0,±1,0)"就说明数组是按哪一侧落位的。
        // 分数小 = 那一侧真的存在于数组里。
        float projectColumnScore = max4(
                Math.abs(DH_PROJ[12]), Math.abs(DH_PROJ[13]),
                Math.abs(Math.abs(DH_PROJ[14]) - 1.0f), Math.abs(DH_PROJ[15]));
        float projectRowScore = max4(
                Math.abs(DH_PROJ[3]), Math.abs(DH_PROJ[7]),
                Math.abs(Math.abs(DH_PROJ[11]) - 1.0f), Math.abs(DH_PROJ[15]));

        boolean rowMajor = projectRowScore < projectColumnScore;
        boolean decisive = Math.abs(projectColumnScore - projectRowScore) > STRUCTURE_TOLERANCE;

        log("  [1] dhProjectionMatrix 第4列吻合=" + fmt(projectColumnScore)
                + " 第4行吻合=" + fmt(projectRowScore)
                + " ⇒ 填充顺序=" + (rowMajor ? "行序" : "列序")
                + (decisive ? "" : "（两者接近，判据不明确）")
                + " ⇒ GLSL 变体位 " + (rowMajor ? 2 : 0)
                + "（" + (rowMajor ? "转置" : "不转置") + "）");

        // 旁证：dhModelViewMatrix 的同一判据。DH 那份平移为 0 ⇒ 通常两边都是 0，
        // 只用来描述"矩阵长什么样"，不参与判定。
        float mvRowScore = max4(Math.abs(DH_MV[12]), Math.abs(DH_MV[13]),
                Math.abs(DH_MV[14]), Math.abs(DH_MV[15] - 1.0f));
        float mvColScore = max4(Math.abs(DH_MV[3]), Math.abs(DH_MV[7]),
                Math.abs(DH_MV[11]), Math.abs(DH_MV[15] - 1.0f));
        log("  [1b] 旁证 dhModelViewMatrix 末行=" + fmt(mvRowScore)
                + " 末列=" + fmt(mvColScore)
                + "；dhProjectionMatrix 第4列=("
                + fmt(DH_PROJ[12]) + ", " + fmt(DH_PROJ[13]) + ", "
                + fmt(DH_PROJ[14]) + ", " + fmt(DH_PROJ[15]) + ")");

        // ---- 2) 视锥一致性：穷举 4 个变体，取夹角最小的 ------------------------
        // 变体位与 rgen 的 dhFarNdc/dhFarWorldPoint 完全相同：
        //   位 0 = NDC 的 y 取反（ySign = -1）
        //   位 1 = 转置（按行序解释数组）
        // 两边（DH 与 MC）用同一个解释，这样"哪个变体让两条射线重合"才有意义。
        float bestAgreement = -1.0f;
        int bestVariant = -1;
        float bestWorstAngle = 180.0f;
        boolean measured = false;

        for (int variant = 0; variant < 4; variant++) {
            boolean transpose = (variant & 2) != 0;
            int ySign = ((variant & 1) == 0) ? 1 : -1;

            float localMinAgreement = 1.0f;
            int used = 0;
            boolean degenerate = false;

            for (float[] probe : PROBES) {
                float x = probe[0];
                float y = ySign * probe[1];

                if (!unproject(DH_INV, x, y, 0.05f, transpose, POINT_A)
                        || !unproject(DH_INV, x, y, 0.95f, transpose, POINT_B)) {
                    degenerate = true;
                    break;
                }
                subtract(POINT_A, POINT_B, DIR_DH);
                if (!normalize(DIR_DH)) {
                    degenerate = true;
                    break;
                }

                if (!unproject(MC_INV, x, y, 0.05f, transpose, POINT_A)
                        || !unproject(MC_INV, x, y, 0.95f, transpose, POINT_B)) {
                    degenerate = true;
                    break;
                }
                subtract(POINT_A, POINT_B, DIR_MC);
                if (!normalize(DIR_MC)) {
                    degenerate = true;
                    break;
                }

                float agreement = Math.abs(dot(DIR_DH, DIR_MC));
                if (agreement < localMinAgreement) {
                    localMinAgreement = agreement;
                }
                used++;
            }

            if (degenerate || used == 0) {
                log("  [2] 变体 " + variant + "：探针退化，跳过");
                continue;
            }

            double worstAngle = Math.toDegrees(Math.acos(clamp(localMinAgreement, -1.0f, 1.0f)));
            log("  [2] 变体 " + variant + "（" + (transpose ? "转置" : "不转置")
                    + "，ySign=" + ySign + "）：最大夹角=" + fmt((float) worstAngle)
                    + "° |dot| 最小=" + fmt(localMinAgreement) + " 探针数=" + used);

            if (!measured || localMinAgreement > bestAgreement) {
                bestAgreement = localMinAgreement;
                bestVariant = variant;
                bestWorstAngle = (float) worstAngle;
                measured = true;
            }
        }

        if (!measured) {
            log("  [3] 结论：视锥一致性测不出来（矩阵非有限？）");
            return;
        }

        // ---- 2b) 角尺度一致性：mode 12 的"屏幕 UV 直通"到底成不成立 ------------
        // 09-24 新增。这是"远场内容整体缩放 / 与几何重叠错位（微缩模型）"那条历史的
        // **量化判据**：mode 12 把 DH 的图按"屏幕 UV 1:1"贴到 miss 像素上，这隐含要求
        // DH 与 MC 的**投影角尺度相同**（FOV + aspect 一致）。
        //
        // 透视投影矩阵里编码角尺度的就是 m00 与 m11：
        //     m11 = 1 / tan(fovY/2)        （纵向）
        //     m00 = m11 / aspect           （横向）
        // 而它们都落在**对角线**上 ⇒ 数组按行序还是列序解释都取同一个元素，
        // 不需要引入变体，比射线夹角那套更直接。
        //
        //   ratio ≈ 1 ⇒ 两套投影角尺度相同 ⇒ 屏幕 UV 直通是精确的；
        //   ratio ≠ 1 ⇒ 贴出来的内容会整体缩放 ⇒ 必须改成按方向采样 DH 图。
        //
        // ⚠ m22/m33 则**允许不同**：DH 的 setDhProjectionMatrix 会改 near/far
        //    （min(near, 7.5) + overdraw 修正），那是深度不是角尺度，不是错位。
        // 用途：以后"远场看起来缩了/错了"先看这一行，一条日志就能分清是角尺度问题
        //      还是几何坐标问题（后者看 [3] 的夹角）。
        final float dhM00 = DH_PROJ[0];
        final float dhM11 = DH_PROJ[5];
        final float mcM00 = MC_PROJ[0];
        final float mcM11 = MC_PROJ[5];
        if (isUsable(mcM00) && isUsable(mcM11) && isUsable(dhM00) && isUsable(dhM11)) {
            final float ratioX = dhM00 / mcM00;
            final float ratioY = dhM11 / mcM11;
            final boolean sameScale = Math.abs(ratioX - 1.0f) < 0.02f && Math.abs(ratioY - 1.0f) < 0.02f;
            log("  [2b] 角尺度 DH/MC：m00=" + fmt(dhM00) + "/" + fmt(mcM00) + "=" + fmt(ratioX)
                    + "  m11=" + fmt(dhM11) + "/" + fmt(mcM11) + "=" + fmt(ratioY)
                    + "  ⇒ " + (sameScale
                    ? "FOV/aspect 一致 ⇒ mode 12 的屏幕 UV 直通成立（贴图不会整体缩放）"
                    : "★FOV/aspect 不一致 ⇒ 屏幕 UV 直通会让远场整体缩放（微缩模型），"
                    + "必须改成按方向采样：用我们的主射线方向穿 DH 投影取 DH 纹素"));
            log("       深度项（允许不同）：m22 DH=" + fmt(DH_PROJ[10]) + " MC=" + fmt(MC_PROJ[10])
                    + "  nearClip DH=" + fmt(param.nearClipPlane)
                    + "  DH 会压 near ⇒ 这里不同是预期的");
        } else {
            log("  [2b] 角尺度一致性测不出来（mcProjectionMatrix 缺字段或非有限）");
        }

        int structuralVariant = rowMajor ? 2 : 0;
        boolean bothAgree = bestVariant == structuralVariant;

        log("  [3] 结论：夹角最小的变体位=" + bestVariant
                + "（最大夹角 " + fmt(bestWorstAngle) + "°）；结构判据给的是变体位 "
                + structuralVariant
                + (bothAgree ? " ⇒ 两条独立证据一致" : " ⇒ ⚠ 两条证据不一致，以 rgen 的自检为准")
                + (bestWorstAngle < 3.0f
                        ? "；矩阵与 MC 相机同源，远场几何与近场共用同一个视锥"
                        : "；⚠ 视锥差异偏大，远场几何会有可见偏移（近场对齐、远处错开）"));
    }

    /**
     * {@code ndc → 世界点}，成功返回 true（w 太小视为退化）。
     *
     * <p>{@code transpose == false}：数组按列优先解释（GLSL 的 {@code mat4} 原样读法）；
     * {@code transpose == true}：等价于 GLSL 里先 {@code transpose()}，即数组按行优先解释。</p>
     */
    private static boolean unproject(float[] m, float x, float y, float z, boolean transpose, float[] out) {
        float px;
        float py;
        float pz;
        float w;

        if (transpose) {
            px = m[0] * x + m[1] * y + m[2] * z + m[3];
            py = m[4] * x + m[5] * y + m[6] * z + m[7];
            pz = m[8] * x + m[9] * y + m[10] * z + m[11];
            w = m[12] * x + m[13] * y + m[14] * z + m[15];
        } else {
            px = m[0] * x + m[4] * y + m[8] * z + m[12];
            py = m[1] * x + m[5] * y + m[9] * z + m[13];
            pz = m[2] * x + m[6] * y + m[10] * z + m[14];
            w = m[3] * x + m[7] * y + m[11] * z + m[15];
        }

        if (!(Math.abs(w) > 1.0e-12f) || !Float.isFinite(w)
                || !Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
            return false;
        }

        out[0] = px / w;
        out[1] = py / w;
        out[2] = pz / w;

        return Float.isFinite(out[0]) && Float.isFinite(out[1]) && Float.isFinite(out[2]);
    }

    /**
     * 非零且有限 —— 比值判据的前置条件。
     *
     * <p>用来挡住"除以 0 → Inf → 比较恒假"这类静默失效：投影矩阵对角元为 0 只可能出现在
     * 尚未填充的矩阵上，那种情况下打印"比例 0.0000 ⇒ 不一致"是误导，不如直接说测不出来。</p>
     */
    private static boolean isUsable(float value) {
        return Float.isFinite(value) && Math.abs(value) > 1.0e-6f;
    }

    private static void safePut(DhApiMat4f matrix, float[] target) {
        if (matrix == null) {
            java.util.Arrays.fill(target, 0.0f);
            return;
        }
        matrix.putValuesInArray(target);
    }

    private static void subtract(float[] a, float[] b, float[] out) {
        out[0] = a[0] - b[0];
        out[1] = a[1] - b[1];
        out[2] = a[2] - b[2];
    }

    private static boolean normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(length > 1.0e-9f) || !Float.isFinite(length)) {
            return false;
        }
        v[0] /= length;
        v[1] /= length;
        v[2] /= length;
        return true;
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float max4(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    static void reset() {
        solveCount = 0;
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
