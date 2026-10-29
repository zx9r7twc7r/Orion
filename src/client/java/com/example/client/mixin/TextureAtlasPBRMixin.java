package com.example.client.mixin;

import com.example.client.access.OmmCutoutClassifier;
import com.example.client.access.PBRAtlasStorage;
import com.example.client.access.SpriteContentsExtension;
import com.example.client.EntityRtCapture;
import com.example.client.ParticleRtCapture;
import com.example.client.mixin.SpriteContentsAccessor;
import com.example.vulkan.NativeVulkan;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

@Mixin(TextureAtlas.class)
public class TextureAtlasPBRMixin {

    // Fully rough, non-metallic, non-emissive dielectric.
    // LabPBR RGBA = smoothness, F0/metal, porosity/SSS, emission.
    // Byte layout is explicitly RGBA (see copyNormalWithAutoPomHeight).
    private static void fillDefaultSpecularAtlas(
            NativeImage destination,
            int width,
            int height
    ) {
        for (int py = 0; py < height; ++py) {
            for (int px = 0; px < width; ++px) {
                long destinationPixel =
                        destination.getPointer() +
                                (
                                        (
                                                (long) py *
                                                        destination.getWidth()
                                        ) +
                                                px
                                ) *
                                        4L;

                MemoryUtil.memPutByte(destinationPixel, (byte) 0);
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) 10);
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) 0);
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) 255);
            }
        }
    }

    // =====================================================
    // Auto-POM height normalization
    //
    // LabPBR normal alpha:
    //   alpha = 255 -> depth 0
    //   smaller alpha -> deeper
    //
    // Use robust 1% / 99% percentiles instead of raw min/max,
    // so isolated bad pixels do not explode the whole sprite.
    //
    // The supplied 256x height-bearing reference has:
    //   P01 = 193
    //   P99 = 245
    //   robust span = 52 alpha levels
    //
    // Treat that as "strong relief" = full current POM depth.
    // =====================================================

    private static final float
            POM_LOW_PERCENTILE = 0.01f;

    private static final float
            POM_HIGH_PERCENTILE = 0.99f;

    private static final int
            POM_MIN_ROBUST_ALPHA_SPAN = 4;

    private static final float
            POM_STRONG_RELIEF_ALPHA_SPAN = 52.0f;


    // =====================================================
    // 2026-10-29：PBR 图集（_n / _s）降采样倍率。
    //
    // 为什么可以缩：着色器用的是**归一化 UV**（MC 的 sprite UV），图集变小后
    // 同一个 UV 仍然落在同一个 sprite 里，所以 sprite 的 atlas 矩形由
    // `sprite.getU0() * atlasWidth` 推出时会自动同比缩小 —— **不需要改 shader、
    // 不需要改 UV、不需要新描述符**。
    //
    // 收益（GPU 显存，两张 RGBA8 + 完整 mip 链 ≈ 10.67 B/像素）：
    //   8192²  682 MiB -> 171 MiB
    //   16384² 2.67 GiB -> 682 MiB
    //
    // 代价：POM 高度场（normal.a）的 texel 变大 2×，视差轮廓的台阶变粗；
    // 法线/粗糙度的微表面细节减半。这类损失是**空间均匀**的，不引入 BC7 那种
    // 块边界与跨 mip 不相关的量化误差。
    //
    // 与 shader 的关系：material_resolve.glsl 的 pbrLod 用
    // `pbrTexelWorld = 1 / (spriteSpan.x * textureSize(blockAtlas))`（albedo 口径）
    // 算，图集减半后该口径比实际偏小 ⇒ lod 偏大（更偏 mip）⇒ 远处略微更糊。
    // 这是**良性**方向（降采样本来就要牺牲细节），所以故意不改 shader。
    //
    // 回退：改回 1 即可（必须同时确认 PBR_ATLAS_DOWNSCALE 与
    // `#define PBR_ATLAS_DOWNSCALE` 无关 —— shader 侧没有这个宏）。
    // =====================================================
    private static final int PBR_ATLAS_DOWNSCALE = 1;

    // 2026-10-29 决定：先退回 1（图集保持原尺寸），改走 BC7 压缩路线。
    // 设 1 时下面所有降采样分支都不触发（图集尺寸不变、copyResampledRgba 走原
    // nearest、copyNormalWithAutoPomHeight 走原循环、[PBR ATLAS] 日志不打印）
    // ⇒ 与加这段代码之前逐位相同。box 滤波函数保留：BC7 的 mip 链生成同样需要它。


    private static int percentileFromHistogram(
            int[] histogram,
            int sampleCount,
            float percentile
    ) {
        if (sampleCount <= 0) {
            return 255;
        }

        int target =
                Math.round(
                        percentile *
                                (sampleCount - 1)
                );

        int cumulative = 0;

        for (int value = 0; value < 256; ++value) {
            cumulative += histogram[value];

            if (cumulative > target) {
                return value;
            }
        }

        return 255;
    }


    private static float clamp01(float value) {
        return Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        value
                )
        );
    }


    // =====================================================
    // Animated `_n` / `_s` textures are stored as frameCount square frames
    // stacked vertically, so the image height is width * frameCount while the
    // stitched sprite rectangle covers exactly ONE frame.
    //
    // Resampling against the whole strip folded every animation frame into one
    // sprite: nearestSourceCoordinate() walked 4 source rows per destination
    // row (prismarine: source 128x512 -> target 128x128), so frame 0..3 were
    // interleaved line by line.  The normal vectors and the LabPBR height alpha
    // were therefore garbage, which showed up as striped/smeared POM relief.
    //
    // Only the first frame is baked: this atlas is uploaded once per resource
    // reload, so it cannot follow the animation anyway.
    // =====================================================
    private static int sourceFrameHeight(NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        if (width > 0 && height > width && height % width == 0) {
            return width;
        }

        return height;
    }


    private static int nearestSourceCoordinate(
            int destinationCoordinate,
            int destinationSize,
            int sourceSize
    ) {
        long centeredDestinationCoordinate =
                (long) destinationCoordinate * 2L + 1L;

        return Math.min(
                sourceSize - 1,
                (int) (
                        centeredDestinationCoordinate *
                                sourceSize /
                                ((long) destinationSize * 2L)
                )
        );
    }


    private static long pixelAddress(
            NativeImage image,
            int x,
            int y
    ) {
        return image.getPointer() +
                (
                        (
                                (long) y *
                                        image.getWidth()
                        ) +
                                x
                ) *
                        4L;
    }


    private static void logResampledPbrTexture(
            String spriteName,
            String textureKind,
            NativeImage source,
            int targetWidth,
            int targetHeight
    ) {
        // 2026-10-29：降采样时每个 sprite 都会与目标尺寸不同，逐条打印会刷几百行；
        // 那种情况由 onUploadPBR 打一行 [PBR ATLAS] 汇总代替。
        if (PBR_ATLAS_DOWNSCALE > 1) {
            return;
        }

        if (
                source.getWidth() != targetWidth ||
                        source.getHeight() != targetHeight
        ) {
            System.out.println(
                    "[PBR ATLAS RESAMPLE] sprite=" +
                            spriteName +
                            " texture=" +
                            textureKind +
                            " source=" +
                            source.getWidth() +
                            "x" +
                            source.getHeight() +
                            " target=" +
                            targetWidth +
                            "x" +
                            targetHeight +
                            " filter=nearest"
            );
        }
    }


    // =====================================================
    // 2026-10-29：区域平均（box filter）重采样 —— 只在 PBR_ATLAS_DOWNSCALE > 1 时走。
    //
    // 为什么不能用 nearest：整张图集缩到 1/2 时，nearest 等于"每 2×2 只留一个
    // texel、其余全丢"，法线/粗糙度会出现点状缺失（比降采样本身更难看，高光更碎）。
    // 区域平均对连续场（法线 RG、粗糙度 R、高度 A）是正确做法。
    //
    // 源范围用整数比例切分：x0 = px*srcW/dstW, x1 = (px+1)*srcW/dstW（至少 1 像素），
    // 于是每个源 texel 恰好被覆盖一次、不重不漏。
    // =====================================================
    private static void copyBoxFilteredRgba(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            int srcWidth,
            int srcHeight
    ) {
        for (int py = 0; py < height; ++py) {
            final int y0 = py * srcHeight / height;
            final int y1 = Math.max(y0 + 1, (py + 1) * srcHeight / height);

            for (int px = 0; px < width; ++px) {
                final int x0 = px * srcWidth / width;
                final int x1 = Math.max(x0 + 1, (px + 1) * srcWidth / width);

                long sumR = 0L;
                long sumG = 0L;
                long sumB = 0L;
                long sumA = 0L;
                int count = 0;

                for (int sy = y0; sy < y1; ++sy) {
                    for (int sx = x0; sx < x1; ++sx) {
                        final long pixel = pixelAddress(source, sx, sy);
                        sumR += MemoryUtil.memGetByte(pixel) & 0xFF;
                        sumG += MemoryUtil.memGetByte(pixel + 1L) & 0xFF;
                        sumB += MemoryUtil.memGetByte(pixel + 2L) & 0xFF;
                        sumA += MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                        ++count;
                    }
                }

                if (count <= 0) {
                    continue;
                }

                final long destinationPixel =
                        pixelAddress(destination, dstX + px, dstY + py);

                MemoryUtil.memPutByte(destinationPixel, (byte) ((sumR + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) ((sumG + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) ((sumB + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) ((sumA + count / 2L) / count));
            }
        }
    }


    // 2026-10-29：法线图集的降采样版本 —— RGB 取区域平均（法线 XY + 材质 AO 都是
    // 连续场），A 取区域平均后走与 nearest 路径**同一套** percentile 重映射
    // （alphaP99 -> 深度 0，alphaP01 -> 深度 1，再乘 reliefStrength）。
    private static void copyBoxFilteredNormalWithHeight(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            int srcWidth,
            int srcHeight,
            int alphaP99,
            int robustSpan,
            float reliefStrength
    ) {
        for (int py = 0; py < height; ++py) {
            final int y0 = py * srcHeight / height;
            final int y1 = Math.max(y0 + 1, (py + 1) * srcHeight / height);

            for (int px = 0; px < width; ++px) {
                final int x0 = px * srcWidth / width;
                final int x1 = Math.max(x0 + 1, (px + 1) * srcWidth / width);

                long sumR = 0L;
                long sumG = 0L;
                long sumB = 0L;
                long sumA = 0L;
                int count = 0;

                for (int sy = y0; sy < y1; ++sy) {
                    for (int sx = x0; sx < x1; ++sx) {
                        final long pixel = pixelAddress(source, sx, sy);
                        sumR += MemoryUtil.memGetByte(pixel) & 0xFF;
                        sumG += MemoryUtil.memGetByte(pixel + 1L) & 0xFF;
                        sumB += MemoryUtil.memGetByte(pixel + 2L) & 0xFF;
                        sumA += MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                        ++count;
                    }
                }

                if (count <= 0 || robustSpan <= 0) {
                    continue;
                }

                final int averageAlpha =
                        (int) ((sumA + count / 2L) / count);

                float normalizedDepth =
                        (alphaP99 - averageAlpha) / (float) robustSpan;

                normalizedDepth = clamp01(normalizedDepth);

                final float finalDepth =
                        normalizedDepth * reliefStrength;

                final int remappedAlpha =
                        Math.round((1.0f - finalDepth) * 255.0f);

                final long destinationPixel =
                        pixelAddress(destination, dstX + px, dstY + py);

                MemoryUtil.memPutByte(destinationPixel, (byte) ((sumR + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) ((sumG + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) ((sumB + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) remappedAlpha);
            }
        }
    }


    private static void copyResampledRgba(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            String spriteName,
            String textureKind
    ) {
        // 2026-10-29：降采样时走区域平均。PBR_ATLAS_DOWNSCALE == 1 时逐位保持原来
        // 的 nearest 行为（包括"源比目标大"的 sprite —— 那条路径不变）。
        if (PBR_ATLAS_DOWNSCALE > 1) {
            copyBoxFilteredRgba(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    source.getWidth(),
                    sourceFrameHeight(source)
            );

            return;
        }

        logResampledPbrTexture(
                spriteName,
                textureKind,
                source,
                width,
                height
        );

        // Animated source: read a single frame instead of folding the whole
        // stacked strip into one sprite (see sourceFrameHeight).
        int sampledSourceHeight =
                sourceFrameHeight(source);

        for (int py = 0; py < height; ++py) {
            int sourceY =
                    nearestSourceCoordinate(
                            py,
                            height,
                            sampledSourceHeight
                    );

            for (int px = 0; px < width; ++px) {
                int sourceX =
                        nearestSourceCoordinate(
                                px,
                                width,
                                source.getWidth()
                        );

                MemoryUtil.memCopy(
                        pixelAddress(source, sourceX, sourceY),
                        pixelAddress(
                                destination,
                                dstX + px,
                                dstY + py
                        ),
                        4L
                );
            }
        }
    }

    // VPT-1.2/1.3: preserve the exact numerical space used by the current
    // UNORM block atlas.  This is deliberately not an sRGB conversion: the
    // closest-hit shader samples the same UNORM values today.
    private static void fillSpriteAverageAlbedo(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height
    ) {
        if (source == null || width <= 0 || height <= 0) {
            return;
        }

        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        long sumA = 0L;

        for (int y = 0; y < source.getHeight(); ++y) {
            for (int x = 0; x < source.getWidth(); ++x) {
                long pixel = pixelAddress(source, x, y);
                int alpha = MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                sumR += (long) (MemoryUtil.memGetByte(pixel) & 0xFF) * alpha;
                sumG += (long) (MemoryUtil.memGetByte(pixel + 1L) & 0xFF) * alpha;
                sumB += (long) (MemoryUtil.memGetByte(pixel + 2L) & 0xFF) * alpha;
                sumA += alpha;
            }
        }

        int r = sumA == 0L ? 0 : (int) ((sumR + sumA / 2L) / sumA);
        int g = sumA == 0L ? 0 : (int) ((sumG + sumA / 2L) / sumA);
        int b = sumA == 0L ? 0 : (int) ((sumB + sumA / 2L) / sumA);

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                long pixel = pixelAddress(destination, dstX + x, dstY + y);
                MemoryUtil.memPutByte(pixel, (byte) r);
                MemoryUtil.memPutByte(pixel + 1L, (byte) g);
                MemoryUtil.memPutByte(pixel + 2L, (byte) b);
                MemoryUtil.memPutByte(pixel + 3L, (byte) 255);
            }
        }
    }


    private static void copyNormalWithAutoPomHeight(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            String spriteName
    ) {
        int[] alphaHistogram =
                new int[256];

        // Animated `_n`: only the first frame is baked into the atlas (see
        // sourceFrameHeight), so the height percentiles must come from that
        // same frame.  Keeping the whole strip here mixed frames that are
        // never sampled together.
        int sampledSourceHeight =
                sourceFrameHeight(source);

        int sampleCount =
                source.getWidth() *
                        sampledSourceHeight;


        for (int py = 0; py < sampledSourceHeight; ++py) {
            for (int px = 0; px < source.getWidth(); ++px) {
                long sourcePixel =
                        pixelAddress(source, px, py);

                // NativeImage.Format.RGBA is physically stored as:
                // byte 0 = R
                // byte 1 = G
                // byte 2 = B
                // byte 3 = A
                int alpha =
                        MemoryUtil.memGetByte(
                                sourcePixel + 3L
                        ) &
                                0xFF;

                ++alphaHistogram[alpha];
            }
        }


        int alphaP01 =
                percentileFromHistogram(
                        alphaHistogram,
                        sampleCount,
                        POM_LOW_PERCENTILE
                );

        int alphaP99 =
                percentileFromHistogram(
                        alphaHistogram,
                        sampleCount,
                        POM_HIGH_PERCENTILE
                );

        int robustSpan =
                alphaP99 -
                        alphaP01;


        // No meaningful height signal:
        // preserve the original normal image exactly.
        if (robustSpan < POM_MIN_ROBUST_ALPHA_SPAN) {
            copyResampledRgba(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    spriteName,
                    "_n"
            );

            return;
        }

        logResampledPbrTexture(
                spriteName,
                "_n",
                source,
                width,
                height
        );


        // Automatic material-specific relief.
        //
        // 52 alpha levels (the supplied strong 256x reference)
        // reaches the full existing POM depth.
        //
        // Smaller-span materials receive proportionally less
        // relief; larger-span materials clamp at full depth.
        float reliefStrength =
                clamp01(
                        robustSpan /
                                POM_STRONG_RELIEF_ALPHA_SPAN
                );


        if (PBR_ATLAS_DOWNSCALE > 1) {
            // 2026-10-29：降采样路径 —— 先对源区域做区域平均，再把平均高度走同一套
            // percentile 重映射。nearest 在 1/2 下会丢掉一半高度样本（POM 轮廓出现
            // 1 texel 的断点），区域平均得到的是连续高度场，代价是台阶整体变粗。
            copyBoxFilteredNormalWithHeight(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    source.getWidth(),
                    sampledSourceHeight,
                    alphaP99,
                    robustSpan,
                    reliefStrength
            );
        } else {

        for (int py = 0; py < height; ++py) {
            int sourceY =
                    nearestSourceCoordinate(
                            py,
                            height,
                            sampledSourceHeight
                    );

            for (int px = 0; px < width; ++px) {
                int sourceX =
                        nearestSourceCoordinate(
                                px,
                                width,
                                source.getWidth()
                        );

                long sourcePixel =
                        pixelAddress(
                                source,
                                sourceX,
                                sourceY
                        );

                int alpha =
                        MemoryUtil.memGetByte(
                                sourcePixel + 3L
                        ) &
                                0xFF;


                // alphaP99 = top / zero-depth reference.
                // alphaP01 = deep reference.
                //
                // At alphaP99 -> normalizedDepth = 0
                // At alphaP01 -> normalizedDepth = 1
                float normalizedDepth =
                        (
                                alphaP99 -
                                        alpha
                        ) /
                                (float) robustSpan;


                normalizedDepth =
                        clamp01(
                                normalizedDepth
                        );


                float finalDepth =
                        normalizedDepth *
                                reliefStrength;


                int remappedAlpha =
                        Math.round(
                                (
                                        1.0f -
                                                finalDepth
                                ) *
                                        255.0f
                        );


                long destinationPixel =
                        pixelAddress(
                                destination,
                                dstX + px,
                                dstY + py
                        );


                // Preserve RGB normal data bit-for-bit.
                // Copy the complete RGBA texel first, then replace only A.
                MemoryUtil.memCopy(
                        sourcePixel,
                        destinationPixel,
                        4L
                );


                MemoryUtil.memPutByte(
                        destinationPixel + 3L,
                        (byte) remappedAlpha
                );
            }
        }

        }


        System.out.println(
                "[POM AUTO HEIGHT] sprite=" +
                        spriteName +
                        " size=" +
                        width +
                        "x" +
                        height +
                        " p01=" +
                        alphaP01 +
                        " p99=" +
                        alphaP99 +
                        " span=" +
                        robustSpan +
                        " relief=" +
                        String.format(
                                java.util.Locale.ROOT,
                                "%.3f",
                                reliefStrength
                        )
        );
    }


    @Inject(method = "upload", at = @At("HEAD"))
    private void onUploadPBR(
            SpriteLoader.Preparations preparations,
            CallbackInfo ci
    ) {
        TextureAtlas self =
                (TextureAtlas) (Object) this;

        // =====================================================
        // PBR Atlas must follow ONLY Minecraft's block atlas.
        //
        // Other atlases (items / particles / etc.) must never
        // overwrite the RT block Normal / Specular atlases.
        // =====================================================

        if (!TextureAtlas.LOCATION_BLOCKS.equals(self.location())) {
            return;
        }

        // The old block atlas and all resource-backed entity/particle views
        // are about to be replaced. Do this before any old NativeImage is
        // closed, so a reused Vulkan handle cannot suppress its re-publication.
        EntityRtCapture.invalidateTextureCache();
        ParticleRtCapture.invalidateTextureCache();

        // Same reason as those two, for the block atlas view itself: the bind
        // hook keeps the previous numeric handle to avoid re-pushing on every
        // terrain draw, and a reused handle value would read as "unchanged".
        PBRAtlasStorage.bumpAtlasEpoch();


        // Cache entries retain references to atlas regions, so invalidate
        // them before closing/replacing the backing NativeImages.
        OmmCutoutClassifier.clearCache();

        if (PBRAtlasStorage.globalNormalAtlas != null) {
            PBRAtlasStorage.globalNormalAtlas.close();
        }
        if (PBRAtlasStorage.globalSpecularAtlas != null) {
            PBRAtlasStorage.globalSpecularAtlas.close();
        }
        if (PBRAtlasStorage.globalVptAlbedoAtlas != null) {
            PBRAtlasStorage.globalVptAlbedoAtlas.close();
        }

        // 2026-10-29：PBR 图集按 1/PBR_ATLAS_DOWNSCALE 构建（GPU 显存 4×）。
        // UV 是归一化的（MC 的 sprite UV），所以下面所有 sprite 矩形
        // （由 sprite.getU0() * atlasWidth 推出）会自动同比缩小 —— 不需要改
        // shader、不需要改 UV、不需要新描述符。详见 PBR_ATLAS_DOWNSCALE 处的说明。
        final int sourceAtlasWidth = preparations.width();
        final int sourceAtlasHeight = preparations.height();

        int atlasWidth = Math.max(1, sourceAtlasWidth / PBR_ATLAS_DOWNSCALE);
        int atlasHeight = Math.max(1, sourceAtlasHeight / PBR_ATLAS_DOWNSCALE);

        if (PBR_ATLAS_DOWNSCALE > 1) {
            System.out.println(
                    "[PBR ATLAS] downscale=" + PBR_ATLAS_DOWNSCALE +
                            " size=" + atlasWidth + "x" + atlasHeight +
                            " (source " + sourceAtlasWidth + "x" + sourceAtlasHeight + ")"
            );
        }

        PBRAtlasStorage.globalNormalAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        PBRAtlasStorage.globalSpecularAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        PBRAtlasStorage.globalVptAlbedoAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        // Never leave atlas regions uninitialized. A sprite without a valid
        // _s texture therefore has the confirmed default dielectric material:
        // RGBA = (0, 10, 0, 255).
        fillDefaultSpecularAtlas(
                PBRAtlasStorage.globalSpecularAtlas,
                atlasWidth,
                atlasHeight
        );


        // 2026-10-29：把每个 sprite 在图集里的矩形（每 4 个 int 一组 x,y,w,h）一次性交给
        // native，供"按 sprite 生成 mip"使用 —— 整图 box 会让双线性足迹在方块边界处跨进
        // 邻居 sprite，mip 越深渗色越宽（mip4 ≈ 16 原 texel ≈ 1/8 方格），表现为方块间的
        // 分界线。下面填的就是循环里已经算好的 atlasSpriteX/Y/W/H，不重算，避免两者漂移。
        final int[] spriteRects =
                new int[preparations.regions().size() * 4];
        int spriteRectCount = 0;

        final List<PBRAtlasStorage.OmmSpriteRegion> ommSpriteRegions =
                new ArrayList<>(preparations.regions().size());

        for (var entry : preparations.regions().entrySet()) {
            String spriteName =
                    entry.getKey().toString();

            TextureAtlasSprite sprite =
                    entry.getValue();

            SpriteContentsExtension ext =
                    (SpriteContentsExtension)
                            sprite.contents();

            NativeImage normalImg =
                    ext.fabric_getNormalImage();

            NativeImage specularImg =
                    ext.fabric_getSpecularImage();

            NativeImage diffuseImg =
                    ((SpriteContentsAccessor) (Object) sprite.contents())
                            .getOriginalImage();

            int atlasSpriteX =
                    Math.max(
                            0,
                            Math.min(
                                    atlasWidth - 1,
                                    Math.round(
                                            sprite.getU0() * atlasWidth
                                    )
                            )
                    );

            int atlasSpriteY =
                    Math.max(
                            0,
                            Math.min(
                                    atlasHeight - 1,
                                    Math.round(
                                            sprite.getV0() * atlasHeight
                                    )
                            )
                    );

            int atlasSpriteWidth =
                    Math.max(
                            1,
                            Math.min(
                                    atlasWidth - atlasSpriteX,
                                    Math.round(
                                            (sprite.getU1() - sprite.getU0()) *
                                                    atlasWidth
                                    )
                            )
                    );

            int atlasSpriteHeight =
                    Math.max(
                            1,
                            Math.min(
                                    atlasHeight - atlasSpriteY,
                                    Math.round(
                                            (sprite.getV1() - sprite.getV0()) *
                                                    atlasHeight
                                    )
                            )
                    );

            // 同一份矩形既用于填充图集，也用于 native 侧的 mip 生成（见上面 spriteRects）。
            if (spriteRectCount * 4 + 4 <= spriteRects.length) {
                spriteRects[spriteRectCount * 4] = atlasSpriteX;
                spriteRects[spriteRectCount * 4 + 1] = atlasSpriteY;
                spriteRects[spriteRectCount * 4 + 2] = atlasSpriteWidth;
                spriteRects[spriteRectCount * 4 + 3] = atlasSpriteHeight;
                ++spriteRectCount;
            }

            // The OMM classifier reads this exact source alpha and only
            // emits a known state when all three UV corners belong to this
            // physical atlas rectangle.  Dynamic sprites remain unknown.
            if (diffuseImg != null) {
                ommSpriteRegions.add(
                        new PBRAtlasStorage.OmmSpriteRegion(
                                sprite.getU0(),
                                sprite.getV0(),
                                sprite.getU1(),
                                sprite.getV1(),
                                diffuseImg,
                                sprite.isAnimated(),
                                PBRAtlasStorage.computeSeeThrough(diffuseImg)
                        )
                );
            }

            fillSpriteAverageAlbedo(
                    diffuseImg,
                    PBRAtlasStorage.globalVptAlbedoAtlas,
                    atlasSpriteX,
                    atlasSpriteY,
                    atlasSpriteWidth,
                    atlasSpriteHeight
            );


            if (normalImg != null) {
                // =================================================
                // AUTO material-level POM:
                //
                // Analyze each sprite independently and bake the
                // remapped LabPBR height directly into the alpha of
                // the RT normal atlas.
                //
                // RGB normal data stays unchanged.
                // No new GPU texture / descriptor / JNI argument.
                // =================================================

                copyNormalWithAutoPomHeight(
                        normalImg,
                        PBRAtlasStorage.globalNormalAtlas,
                        atlasSpriteX,
                        atlasSpriteY,
                        atlasSpriteWidth,
                        atlasSpriteHeight,
                        spriteName
                );
            }


            if (specularImg != null) {
                copyResampledRgba(
                        specularImg,
                        PBRAtlasStorage.globalSpecularAtlas,
                        atlasSpriteX,
                        atlasSpriteY,
                        atlasSpriteWidth,
                        atlasSpriteHeight,
                        spriteName,
                        "_s"
                );
            }
        }

        PBRAtlasStorage.setOmmSpriteRegions(ommSpriteRegions);


        // === 核心内存透传防线 ===
        // 提取堆外内存的绝对地址（Pointer），下发至 C++ 层进行 Vulkan Image 的显存重组
        long normalPtr =
                PBRAtlasStorage.globalNormalAtlas.getPointer();

        long specularPtr =
                PBRAtlasStorage.globalSpecularAtlas.getPointer();


        NativeVulkan.uploadPBRAtlases(
                normalPtr,
                specularPtr,
                atlasWidth,
                atlasHeight,
                spriteRects,
                spriteRectCount
        );


        // 物理日志防线：确认指针是否成功下放
        System.out.println(
                "[PBR Pipeline] Uploading Atlases to C++. NormalPtr: " +
                        normalPtr +
                        ", SpecularPtr: " +
                        specularPtr
        );

        // =====================================================================
        // 2026-10-29：上传完成即**退役 CPU 副本**（显存/内存账）。
        //
        // 依据（全仓引用扫描）：globalNormalAtlas / globalSpecularAtlas 一共 17 处
        // 引用，全部在本文件内 —— 创建（上面 new NativeImage）、逐 sprite 填充、
        // 取 getPointer() 交给 JNI、以及下次重载时 close。**上传之后没有任何读取点**：
        //   · SectionCompiler 读的是 globalVptAlbedoAtlas（那份必须留着）；
        //   · OMM 分类用的是 OmmSpriteRegion 里的 diffuseImg（原始 sprite），不是图集。
        //
        // 为什么可以立刻关：NativeVulkan.uploadPBRAtlases 是同步 JNI，
        // cpp/VulkanCore.cpp 的 uploadTextureToVRAM 在返回前已完成
        // std::memcpy(stagingMapped, pixels, imageSize)（:11922）；specular 那次进入时
        // 还会先等 normal 的 fence。所以本行之后 Java 侧这块堆外内存已无人引用。
        //
        // 收益：2 × W×H×4 的堆外内存 —— 4096²=128MiB / 8192²=512MiB / 16384²=2GiB。
        // 注意这是**进程私有内存**，不是 GPU 显存；它缓解的是 WDDM 下的提交流量/
        // 换页（见 VulkanCore.cpp:19396 那段实测），对"显存爆掉"只有间接帮助。
        //
        // ⚠ 契约变更：这两个字段从此是"上传后即退役"（可能为 null）。将来若有人要
        //   在 CPU 侧读图集（例如 OMM 改读 atlas alpha），必须改成延迟关闭，不能假设
        //   它一直有效。
        // =====================================================================
        PBRAtlasStorage.globalNormalAtlas.close();
        PBRAtlasStorage.globalNormalAtlas = null;

        PBRAtlasStorage.globalSpecularAtlas.close();
        PBRAtlasStorage.globalSpecularAtlas = null;
    }
}
