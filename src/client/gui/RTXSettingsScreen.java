package com.example.client.gui;

import com.example.vulkan.NativeVulkan;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import com.example.client.config.RTXSettingsConfig;

public final class RTXSettingsScreen extends OptionsSubScreen {


    private static final Component TITLE =
            Component.literal("RTX 设置");
    private int pendingReflexMode;

    private int pendingRRMode;

    private float pendingNoDirectLightAmbient;

    private float pendingPOMDepth;

    private boolean pendingHDR;


    public RTXSettingsScreen(
            Screen lastScreen,
            Options options
    ) {
        super(
                lastScreen,
                options,
                TITLE
        );


        this.pendingReflexMode =
                RTXSettingsConfig.getReflexMode();

        this.pendingRRMode =
                RTXSettingsConfig.getRRMode();

        this.pendingNoDirectLightAmbient =
                RTXSettingsConfig.getNoDirectLightAmbient();

        this.pendingPOMDepth =
                RTXSettingsConfig.getPOMDepth();

        this.pendingHDR =
                RTXSettingsConfig.isHDREnabled();
    }


    @Override
    protected void addOptions() {

        final boolean reflexAvailable =
                NativeVulkan.isLoaded()
                        && NativeVulkan.isReflexAvailable();


        final int currentMode =
                this.pendingReflexMode;


        CycleButton<Integer> reflexButton =
                CycleButton.builder(
                                mode -> {
                                    if(!reflexAvailable) {
                                        return Component.literal(
                                                "不可用"
                                        );
                                    }


                                    return switch(mode) {
                                        case 0 ->
                                                Component.literal(
                                                        "Off"
                                                );

                                        case 1 ->
                                                Component.literal(
                                                        "On"
                                                );

                                        case 2 ->
                                                Component.literal(
                                                        "On + Boost"
                                                );

                                        default ->
                                                Component.literal(
                                                        "On"
                                                );
                                    };
                                },
                                currentMode
                        )
                        .withValues(
                                0,
                                1,
                                2
                        )
                        .create(
                                Component.literal(
                                        "NVIDIA Reflex 低延迟"
                                ),
                                (button, mode) -> {

                                    if(!reflexAvailable) {
                                        return;
                                    }


                                    this.pendingReflexMode =
                                            mode;
                                }
                        );


        reflexButton.active =
                reflexAvailable;


        this.list.addBig(
                reflexButton
        );
        // =====================================================
// DLSS Ray Reconstruction quality
// =====================================================

        final int currentRRMode =
                this.pendingRRMode;


        CycleButton<Integer> rrQualityButton =
                CycleButton.builder(
                                mode -> switch(mode) {

                                    case RTXSettingsConfig.RR_MODE_PERFORMANCE ->
                                            Component.literal(
                                                    "Performance"
                                            );

                                    case RTXSettingsConfig.RR_MODE_BALANCED ->
                                            Component.literal(
                                                    "Balanced"
                                            );

                                    case RTXSettingsConfig.RR_MODE_QUALITY ->
                                            Component.literal(
                                                    "Quality"
                                            );

                                    case RTXSettingsConfig.RR_MODE_DLAA ->
                                            Component.literal(
                                                    "DLAA"
                                            );

                                    default ->
                                            Component.literal(
                                                    "Quality"
                                            );
                                },
                                currentRRMode
                        )
                        .withValues(
                                RTXSettingsConfig.RR_MODE_PERFORMANCE,
                                RTXSettingsConfig.RR_MODE_BALANCED,
                                RTXSettingsConfig.RR_MODE_QUALITY,
                                RTXSettingsConfig.RR_MODE_DLAA
                        )
                        .create(
                                Component.literal(
                                        "DLSS 光线重构质量"
                                ),
                                (button, mode) ->
                                        this.pendingRRMode =
                                                mode
                        );


        this.list.addBig(
                rrQualityButton
        );
        // =====================================================
// No-direct-light ambient brightness
//
// range: 0.00 - 1.00
// step : 0.01
// default: 0.10
// =====================================================

        AbstractSliderButton ambientBrightnessSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingNoDirectLightAmbient
                ) {

                    @Override
                    protected void updateMessage() {

                        double snapped =
                                Math.round(
                                        this.value * 100.0
                                ) / 100.0;


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "无直射光环境亮度: %.2f",
                                                snapped
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {

                        double snapped =
                                Math.round(
                                        this.value * 100.0
                                ) / 100.0;


                        this.value =
                                snapped;


                        RTXSettingsScreen.this.pendingNoDirectLightAmbient =
                                (float) snapped;


                        this.updateMessage();
                    }
                };


        this.list.addBig(
                ambientBrightnessSlider
        );

        // =====================================================
        // POM parallax depth
        //
        // range: 0.00 - 0.20
        // step : 0.01
        // default: 0.10
        // Applied when the existing Confirm button is pressed.
        // =====================================================

        AbstractSliderButton pomDepthSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingPOMDepth / 0.20
                ) {

                    private double snappedDepth() {
                        return Math.round(
                                this.value *
                                        0.20 *
                                        100.0
                        ) / 100.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double depth =
                                this.snappedDepth();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "视差深度: %.2f",
                                                depth
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double depth =
                                this.snappedDepth();


                        this.value =
                                depth / 0.20;


                        RTXSettingsScreen.this.pendingPOMDepth =
                                (float) depth;


                        this.updateMessage();
                    }
                };


        this.list.addBig(
                pomDepthSlider
        );
        final boolean currentHDR =
                this.pendingHDR;


        CycleButton<Boolean> hdrButton =
                CycleButton.builder(
                                enabled ->
                                        Component.literal(
                                                enabled
                                                        ? "On"
                                                        : "Off"
                                        ),
                                currentHDR
                        )
                        .withValues(
                                false,
                                true
                        )
                        .create(
                                Component.literal(
                                        "HDR"
                                ),
                                (button, enabled) ->
                                        this.pendingHDR =
                                                enabled
                        );


        this.list.addBig(
                hdrButton
        );
    }


    private void applyPendingSettings() {

        boolean allApplied =
                true;


        // =====================================================
        // NVIDIA Reflex
        // =====================================================

        final boolean reflexAvailable =
                NativeVulkan.isLoaded()
                        && NativeVulkan.isReflexAvailable();


        if(reflexAvailable)
        {
            if(
                    NativeVulkan.setReflexMode(
                            this.pendingReflexMode
                    )
            )
            {
                RTXSettingsConfig.setReflexMode(
                        this.pendingReflexMode
                );
            }
            else
            {
                this.pendingReflexMode =
                        NativeVulkan.getReflexMode();

                allApplied =
                        false;
            }
        }


        // =====================================================
        // DLSS Ray Reconstruction
        // =====================================================

        if(
                NativeVulkan.setRRMode(
                        this.pendingRRMode
                )
        )
        {
            RTXSettingsConfig.setRRMode(
                    this.pendingRRMode
            );
        }
        else
        {
            this.pendingRRMode =
                    NativeVulkan.getRRMode();

            allApplied =
                    false;
        }


        // =====================================================
        // No-direct-light ambient
        // =====================================================

        if(
                NativeVulkan.setNoDirectLightAmbient(
                        this.pendingNoDirectLightAmbient
                )
        )
        {
            RTXSettingsConfig.setNoDirectLightAmbient(
                    this.pendingNoDirectLightAmbient
            );
        }
        else
        {
            this.pendingNoDirectLightAmbient =
                    NativeVulkan.getNoDirectLightAmbient();

            allApplied =
                    false;
        }



        // =====================================================
        // POM parallax depth
        // =====================================================

        if(
                NativeVulkan.setPOMDepth(
                        this.pendingPOMDepth
                )
        )
        {
            RTXSettingsConfig.setPOMDepth(
                    this.pendingPOMDepth
            );
        }
        else
        {
            this.pendingPOMDepth =
                    NativeVulkan.getPOMDepth();

            allApplied =
                    false;
        }

        // =====================================================
        // HDR
        //
        // HDR native output is connected in HDR-2.
        // For HDR-1 this only commits the confirmed setting.
        // =====================================================

        RTXSettingsConfig.setHDREnabled(
                this.pendingHDR
        );


        System.out.println(
                allApplied
                        ? "[RTX SETTINGS] Confirmed"
                        : "[RTX SETTINGS] Confirmed with native failure"
        );
    }
    @Override
    protected void addFooter() {

        LinearLayout footer =
                LinearLayout.horizontal()
                        .spacing(
                                8
                        );


        footer.addChild(
                Button.builder(
                                Component.literal(
                                        "确认"
                                ),
                                button ->
                                        this.applyPendingSettings()
                        )
                        .width(
                                150
                        )
                        .build()
        );


        footer.addChild(
                Button.builder(
                                Component.literal(
                                        "返回"
                                ),
                                button ->
                                        this.onClose()
                        )
                        .width(
                                150
                        )
                        .build()
        );


        this.layout.addToFooter(
                footer
        );
    }


}