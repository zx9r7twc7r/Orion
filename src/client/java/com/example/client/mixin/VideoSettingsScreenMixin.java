package com.example.client.mixin;

import com.example.client.gui.RTXSettingsScreen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin
        extends OptionsSubScreen {


    protected VideoSettingsScreenMixin(
            Screen lastScreen,
            Options options,
            Component title
    ) {
        super(
                lastScreen,
                options,
                title
        );
    }


    @Inject(
            method = "addOptions",
            at = @At("TAIL")
    )
    private void rtx$addSettingsButton(
            CallbackInfo ci
    ) {
        Button rtxSettingsButton =
                Button.builder(
                                Component.literal(
                                        "RTX 设置"
                                ),
                                button ->
                                        this.minecraft.gui.setScreen(
                                                new RTXSettingsScreen(
                                                        this,
                                                        this.options
                                                )
                                        )
                        )
                        .bounds(
                                0,
                                0,
                                310,
                                20
                        )
                        .build();


        this.list.addBig(
                rtxSettingsButton
        );
    }
}