package com.example.client.gui;

import com.example.vulkan.NativeVulkan;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import com.example.client.config.RTXSettingsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.DoubleConsumer;

public final class RTXSettingsScreen extends OptionsSubScreen {


    private static final Component TITLE =
            Component.literal("RTX 设置");
    private int pendingReflexMode;

    private int pendingRRMode;

    private int pendingGiBounces;
    private int pendingAoNearSamples;
    private int pendingVolumetricRestir;
    private int pendingAoMidSamples;
    private int pendingSunShadowSamples;
    private int pendingLocalLightRisCandidates;
    private int pendingLocalLightAreaSamples;
    private int pendingWaterReflectionInterval;
    private int pendingGlossyFullDistance;
    private int pendingDynamicRtasInterval;
    private int pendingMaxRtParticles;
    private float pendingRtSpp;

    private float pendingNoDirectLightAmbient;

    private float pendingPOMDepth;
    private boolean pendingPOMSharp;

    private int pendingGiNearRate;
    private boolean pendingGlossyRoughLod;

    private final float[] pendingUeCloud =
            RTXSettingsConfig.getUeCloudAll();

    private final String[] UE_CLOUD_LABELS = {
            "多散射 octaves", "  每阶贡献", "  每阶遮蔽", "散射亮度补偿",
            "地面反照贡献", "云环境光遮蔽", "逐采样大气透射", "天气图频率倍率"
    };
    private final float[] UE_CLOUD_MIN = {1.0f, 0.0f, 0.05f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    private final float[] UE_CLOUD_MAX = {3.0f, 1.0f, 1.00f, 3.0f, 1.0f, 4.0f, 2.0f, 4.0f};
    private final float[] UE_CLOUD_STEP = {1.0f, 0.05f, 0.05f, 0.05f,
            0.05f, 0.10f, 0.05f, 0.10f};

    private AbstractSliderButton makeUeCloudSlider(
            int index,
            String label,
            float min,
            float max,
            float step
    ) {
        return new AbstractSliderButton(
                0,
                0,
                310,
                20,
                Component.empty(),
                (this.pendingUeCloud[index] - min) / (max - min)
        ) {

            {
                this.updateMessage();
            }


            private float snapped() {
                final float raw =
                        min + (float) this.value * (max - min);
                final float snapped =
                        Math.round(raw / step) * step;
                return Math.max(min, Math.min(max, snapped));
            }


            @Override
            protected void updateMessage() {
                this.setMessage(
                        Component.literal(
                                String.format(
                                        java.util.Locale.ROOT,
                                        "%s: %.2f",
                                        label,
                                        this.snapped()
                                )
                        )
                );
            }


            @Override
            protected void applyValue() {
                final float value =
                        this.snapped();

                this.value =
                        (value - min) / (max - min);

                RTXSettingsScreen.this.pendingUeCloud[index] =
                        value;

                this.updateMessage();
            }
        };
    }
    private boolean pendingTrueSubsurfaceScattering;
    private float pendingLocalLightMaxDistance;
    private float pendingLocalLightIntensity;
    private float pendingDynamicLightIntensity;
    private float pendingSunDiskLuminance;
    private float pendingSunHaloStrength;
    private float pendingSunAngularDiameterDegrees;
    private float pendingStarIntensity;
    private float pendingGalaxyScale;
    private float pendingGalaxyTiltDegrees;
    private float pendingGalaxyYawDegrees;
    private float pendingGalaxyMix;
    private float pendingSunTintStrength;
    private float pendingAuroraBrightness;
    private float pendingAuroraFlowSpeed;
    private boolean pendingPhysicalSkyEnabled;
    private float pendingScatterIntensity;
    private float pendingOzoneStrength;
    private float pendingPowderStrength;
    private float pendingCloudSunForwardScattering;
    private float pendingCloudSunReddening;
    private float pendingCloudTopStretch;
    private float pendingCloudDetileStrength;
    private float pendingCloudType;
    private float pendingCloudErosionScale;
    private float pendingCloudWeatherScale;
    private float pendingCloudSilver;
    private float pendingCloudViewDistance;

    private boolean pendingCloudEnabled;
    private float pendingCloudBottom;
    private float pendingCloudThickness;
    private float pendingCloudCoverage;
    private float pendingCloudDensity;
    private float pendingCloudDetailStrength;
    private float pendingCloudWindSpeed;
    private float pendingCloudEvolution;
    private int pendingCloudQuality;
    private float pendingCloudSize;
    private float pendingCloudDistribution;
    private float pendingCloudBaseFeather;
    private float pendingCloudSunlightStrength;
    private float msPhiIntensity;
    private float msPhiDepthPow;
    private float msPhiDepthBias;
    private float msOctaveStrength;
    private float msAdaptiveLightRange;
    private float msFarFadeDistance;

    private float phiBoundaryConfidence;
    private float phiMsBuildScale;
    private float phiCompress;
    private float phiConeRatio;

    private float lightSourceOdScale;
    private float lightSourceCurvePow;
    private float lightAoUpwardScale;
    private float lightSunsetAmbientTint;

    private float shapeBottomSmoothHeight;
    private float shapeBottomSmoothPow;
    private float shapeTwoTierThreshold;
    private float moonStrength;
    private float moonPhase;

    private float shapeDetailScale;
    private float shapeDetailContrast;
    private float shapeProfileCurve;

    private float ambTopMul;
    private float ambBottomMul;
    private float ambSunSide;
    private float ambHueFollow;
    private float skyHueCool;
    private float skyHueEnergy;
    private float skyDirStrength;
    private float ambientUnify;
    private float cloudGroundAlbedo;
    private float cloudGroundBounce;
    private float cloudSunlightResidual;
    private float cloudPhaseFloor;
    private float ms2ScatterTint;
    private float ms2Octaves;
    private float ms2Attenuation;
    private float ms2Eccentricity;
    private float l2SunPathReddening;
    private float l2AtmosphereExtinction;
    private float l2PowderMode;
    private float profileType;
    private float profileTypeSpread;
    private float wispyStrength;
    private float wispyEdgeWidth;
    private float wispyReach;
    private float wispyTopHeight;
    private float cloudSkyVisibilityStrength;
    private float cloudSkyVisibilitySteps;
    private float cloudTemporalDampStrength;
    private float cloudBottomOcclusion;
    private float fogExtrasDynamicLightStrength;
    private float fogExtrasGroundAnchorStrength;
    private float fogExtrasSkyAmbientStrength;
    private float fogExtrasTemporalJitterStrength;
    private float cloudAerialStrength;
    private float groundAerialStrength;
    private float groundAerialOcclusion;
    private boolean msTwoTierStep;
    private boolean msMeanReproject;
    private float wpCrestBacklit;
    private float wpCausticDispersion;
    private float wpCausticDepthDiffusion;
    private boolean wpPhysicalPhase;

    private float waterWaveCalibration;
    private float waterWaveStrength;
    private float waterWaveBlend;
    private float waterReflectCausticStrength;
    private float waterSimSpongeWidth;
    private float waterSimGaussianSource;
    private float waterSimDampingScale;
    private float waterSimPropagationSpeed;
    private float waterSssNonlinear;
    private float waterSssBoost;
    private float waterSssPhaseG;
    private float waterSssStrength;
    private float waterWaveLengthScale;
    private float waterWaveDirection;
    private float waterWaveCrestSharpness;
    private float waterWaveWarpStrength;
    private float waterWaveAflScale;
    private float waterWaveAflLayers;
    private boolean waterFftEnabled;
    private float waterFftAmplitude;
    private float waterFftChoppiness;
    private float waterFftTilePeriod;
    private float waterFftDetail;
    private int postTonemapMode;
    private float postExposureStrength;
    private float postContrast;
    private float postSaturation;
    private float postGamma;
    private int postMirrorBounceCount;
    private int postTlasCullDistance;
    private int postTlasFrustumCullMargin;
    private int postRrFarCloudDistance;
    private float postBloomStrength;
    

    private float postGainR = 1.0f;
    private float postGainG = 1.0f;
    private float postGainB = 1.0f;

    private boolean pendingFogEnabled;
    private float pendingFogDensity;
    private float pendingFogHeight;
    private float pendingFogScaleHeight;
    private float pendingFogAnisotropy;
    private float pendingFogSunStrength;
    private float pendingFogAmbientStrength;
    private float pendingFogMaxDistance;
    private float pendingFogStartDistance;
    private int pendingFogSunSteps;
    private float pendingFogRainBoost;
    private boolean pendingFogWispEnabled;
    private float pendingFogWispStrength;
    private float pendingFogWispWavelength;
    private float pendingFogWispSpeed;
    private boolean pendingGodRayEnabled;
    private int pendingGodRaySteps;
    private int pendingGodRayOcclusionSamples;
    private float pendingGodRayIntensity;
    private float pendingGodRayReprojection;
    private float pendingRainOpacity;
    private float pendingRainStreakLength;
    private float pendingRainBrightness;
    private float pendingRainWidth;
    private float pendingRainDensityScale;
    private float pendingSnowSize;
    private float pendingSnowDensity;
    private float pendingSnowOpacity;
    private float pendingSnowBrightness;
    private float pendingSnowFallSpeed;
    private float pendingSnowSway;
    private boolean pendingRainWallEnabled;
    private float pendingRainWallWetness;
    private float pendingRainWallDripDensity;
    private float pendingRainWallDripSpeed;
    private float pendingRainWallDripSize;
    private float pendingRainWallDripBump;
    private float pendingRainWallDistance;

    private float pendingWaterStyleWaveScale;
    private float pendingWaterStyleWaveSpeed;
    private float pendingWaterStyleSwellScale;
    private float pendingWaterStyleMidScale;
    private float pendingWaterStyleFineScale;
    private float pendingWaterStyleTintMix;
    private float pendingWaterStyleDensityScale;
    private float pendingWaterStyleCausticStrength;

    private boolean pendingHDR;

    private boolean pendingAutoExposureEnabled;
    private float pendingAutoExposureCompensation;

    private boolean pendingFGFrameSync;

    private boolean pendingFGRefreshCap;

    private boolean pendingSLDevPlugins;

    private boolean pendingSerEnabled;

    private int pendingMotionVectors;

    private int pendingFGMultiplier;

    private int pendingFGBaseFrameLimit;

    private boolean pendingArmorMetal;

    private boolean pendingCloudFadeFollowsRrMode;

    private boolean pendingCloudPostRrComposite;

    private int pendingCloudResFloorPercent;
    private float pendingCloudFarStepDensity;

    private boolean pendingPlaneCloudEnabled;
    private float pendingPlaneCloudHeightOffset;
    private float pendingPlaneCloudScale;
    private float pendingPlaneCloudSpeed;
    private float pendingPlaneCloudDensity;
    private float pendingPlaneCloudCoverage;
    private float pendingPlaneCloudOctaves;
    private float pendingPlaneCloudFadeDistance;
    private float pendingPlaneCloudBrightness;

    private int pendingGiRoulette;
    private int pendingSunMarchFarDistance;

    private int pendingMotionDebugView;

    private int pendingDirectLightMode;
    private int pendingIndirectMode;
    private int pendingSparseRatePercent;


    private final List<AbstractWidget> optionRow =
            new ArrayList<>();

    private static final int PAGE_HUB = -1;
    private static final int PAGE_UPSCALER = 0;
    private static final int PAGE_PERF = 1;
    private static final int PAGE_SKY = 2;
    private static final int PAGE_CLOUD = 3;
    private static final int PAGE_FOG = 4;
    private static final int PAGE_RAIN = 5;
    private static final int PAGE_WATER = 6;
    private static final int PAGE_MATERIAL = 7;
    private static final int PAGE_POST = 8;
    private static final int PAGE_COLORGRADE = 9;

    private int page = PAGE_HUB;

    private int section = PAGE_HUB;


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

        this.pendingGiBounces =
                RTXSettingsConfig.getPerformanceSettings().giBounces();
        RTXSettingsConfig.PerformanceSettings performance =
                RTXSettingsConfig.getPerformanceSettings();
        this.pendingAoNearSamples = performance.aoNearSamples();
        this.pendingVolumetricRestir = performance.volumetricRestir();
        this.pendingAoMidSamples = performance.aoMidSamples();
        this.pendingSunShadowSamples = performance.sunShadowSamples();
        this.pendingLocalLightRisCandidates = performance.localLightRisCandidates();
        this.pendingLocalLightAreaSamples = performance.localLightAreaSamples();
        this.pendingWaterReflectionInterval = performance.waterReflectionInterval();
        this.pendingGlossyFullDistance = performance.glossyFullDistance();
        this.pendingDynamicRtasInterval = performance.dynamicRtasInterval();
        this.pendingMaxRtParticles = performance.maxRtParticles();
        this.pendingRtSpp = performance.rtSpp();

        this.pendingNoDirectLightAmbient =
                RTXSettingsConfig.getNoDirectLightAmbient();

        this.pendingPOMDepth =
                RTXSettingsConfig.getPOMDepth();

        this.pendingPOMSharp =
                RTXSettingsConfig.isPOMSharp();

        this.pendingGiNearRate =
                NativeVulkan.getGiNearRate();

        this.pendingGlossyRoughLod =
                NativeVulkan.getGlossyRoughLod();

        this.pendingTrueSubsurfaceScattering =
                RTXSettingsConfig.isTrueSubsurfaceScattering();

        this.pendingLocalLightMaxDistance =
                RTXSettingsConfig.getLocalLightMaxDistance();

        this.pendingLocalLightIntensity =
                RTXSettingsConfig.getLocalLightIntensity();

        this.pendingDynamicLightIntensity =
                RTXSettingsConfig.getDynamicLightIntensity();

        RTXSettingsConfig.SunVisualSettings sunVisuals =
                RTXSettingsConfig.getSunVisualSettings();
        this.pendingSunDiskLuminance = sunVisuals.diskLuminance();
        this.pendingSunHaloStrength = sunVisuals.haloStrength();
        this.pendingSunAngularDiameterDegrees = sunVisuals.angularDiameterDegrees();

        RTXSettingsConfig.AtmosphereSettings atmosphere =
                RTXSettingsConfig.getAtmosphereSettings();
        this.pendingStarIntensity = atmosphere.starIntensity();
        this.pendingSunTintStrength = atmosphere.sunTintStrength();
        this.pendingPhysicalSkyEnabled = atmosphere.physicalSkyEnabled();
        this.pendingScatterIntensity = atmosphere.scatterIntensity();
        this.pendingOzoneStrength = atmosphere.ozoneStrength();
        this.pendingPowderStrength =
                RTXSettingsConfig.getPowderStrength();
        this.pendingAuroraBrightness =
                RTXSettingsConfig.getAuroraBrightness();
        this.pendingAuroraFlowSpeed =
                RTXSettingsConfig.getAuroraFlowSpeed();
        this.pendingCloudSunForwardScattering =
                RTXSettingsConfig.getCloudSunForwardScattering();
        this.pendingCloudSunReddening =
                RTXSettingsConfig.getCloudSunReddening();
        this.pendingCloudTopStretch =
                RTXSettingsConfig.getCloudTopStretch();
        this.pendingCloudDetileStrength =
                RTXSettingsConfig.getCloudDetileStrength();
        this.pendingCloudType = RTXSettingsConfig.getCloudType();
        this.pendingCloudErosionScale = RTXSettingsConfig.getCloudErosionScale();
        this.pendingCloudWeatherScale = RTXSettingsConfig.getCloudWeatherScale();
        this.pendingCloudSilver = RTXSettingsConfig.getCloudSilver();
        this.pendingCloudViewDistance = RTXSettingsConfig.getCloudViewDistance();
        this.loadCloudMarchSettings(RTXSettingsConfig.getCloudMarchSettings());
        this.loadWaterParamsSettings(RTXSettingsConfig.getWaterParamsSettings());
        this.loadWaterWaveSettings(RTXSettingsConfig.getWaterWaveSettings());
        this.loadWaterSimWaveSettings(RTXSettingsConfig.getWaterSimWaveSettings());
        this.loadWaterWaveShapeSettings(RTXSettingsConfig.getWaterWaveShapeSettings());
        this.loadWaterWaveAflSettings(RTXSettingsConfig.getWaterWaveAflSettings());
        this.loadWaterSssSettings(RTXSettingsConfig.getWaterSssSettings());
        this.loadWaterFftSettings(RTXSettingsConfig.getWaterFftSettings());
        this.loadPostProcessingSettings(RTXSettingsConfig.getPostProcessingSettings());

        RTXSettingsConfig.CloudSettings clouds =
                RTXSettingsConfig.getCloudSettings();
        this.loadCloudSettings(clouds);
        this.loadCloudMsSettings(RTXSettingsConfig.getCloudMsSettings());
        this.loadCloudPhiSettings(RTXSettingsConfig.getCloudPhiSettings());
        this.loadCloudLightSettings(RTXSettingsConfig.getCloudLightSettings());
        this.loadCloudShapeSettings(RTXSettingsConfig.getCloudShapeSettings());
        this.loadCloudMoonSettings(RTXSettingsConfig.getCloudMoonSettings());
        this.loadCloudShape2Settings(RTXSettingsConfig.getCloudShape2Settings());
        this.loadCloudAmbientSettings(RTXSettingsConfig.getCloudAmbientSettings());
        this.loadCloudSkyHueSettings(RTXSettingsConfig.getCloudSkyHueSettings());
        this.loadCloudPhysicsSettings(RTXSettingsConfig.getCloudPhysicsSettings());
        this.loadCloudMs2Settings(RTXSettingsConfig.getCloudMs2Settings());
        this.loadCloudLight2Settings(RTXSettingsConfig.getCloudLight2Settings());
        this.loadCloudProfileSettings(RTXSettingsConfig.getCloudProfileSettings());
        this.loadCloudDetailSettings(RTXSettingsConfig.getCloudDetailSettings());
        this.loadCloudTemporalSettings(RTXSettingsConfig.getCloudTemporalSettings());

        this.loadFogExtrasSettings(RTXSettingsConfig.getFogExtrasSettings());

        RTXSettingsConfig.FogWispSettings fogWisp = RTXSettingsConfig.getFogWispSettings();
        this.pendingFogWispEnabled = fogWisp.enabled();
        this.pendingFogWispStrength = fogWisp.strength();
        this.pendingFogWispWavelength = fogWisp.wavelength();
        this.pendingFogWispSpeed = fogWisp.speed();

        this.loadCloudAerialSettings(RTXSettingsConfig.getCloudAerialSettings());

        RTXSettingsConfig.VolumetricFogSettings fog =
                RTXSettingsConfig.getVolumetricFogSettings();
        this.pendingFogEnabled = fog.enabled();
        this.pendingFogDensity = fog.density();
        this.pendingFogHeight = fog.height();
        this.pendingFogScaleHeight = fog.scaleHeight();
        this.pendingFogAnisotropy = fog.anisotropy();
        this.pendingFogSunStrength = fog.sunStrength();
        this.pendingFogAmbientStrength = fog.ambientStrength();
        this.pendingFogMaxDistance = fog.maxDistance();
        this.pendingFogStartDistance = fog.startDistance();
        this.pendingFogSunSteps = fog.sunSteps();
        this.pendingFogRainBoost = fog.rainBoost();

        RTXSettingsConfig.VolumetricLightSettings godRays =
                RTXSettingsConfig.getVolumetricLightSettings();
        this.pendingGodRayEnabled = godRays.enabled();
        this.pendingGodRaySteps = godRays.steps();
        this.pendingGodRayOcclusionSamples = godRays.occlusionSamples();
        this.pendingGodRayIntensity = godRays.intensity();
        this.pendingGodRayReprojection = godRays.reprojection();

        RTXSettingsConfig.RainSettings rainLook =
                RTXSettingsConfig.getRainSettings();
        this.pendingRainOpacity = rainLook.opacity();
        this.pendingRainStreakLength = rainLook.streakLength();
        this.pendingRainBrightness = rainLook.brightness();
        this.pendingRainWidth = rainLook.width();
        this.pendingRainDensityScale = rainLook.densityScale();

        RTXSettingsConfig.SnowSettings snowLook =
                RTXSettingsConfig.getSnowSettings();
        this.pendingSnowSize = snowLook.sizeScale();
        this.pendingSnowDensity = snowLook.densityScale();
        this.pendingSnowOpacity = snowLook.opacity();
        this.pendingSnowBrightness = snowLook.brightness();
        this.pendingSnowFallSpeed = snowLook.fallSpeedScale();
        this.pendingSnowSway = snowLook.swayBlocks();

        RTXSettingsConfig.RainWallSettings rainWall =
                RTXSettingsConfig.getRainWallSettings();
        this.pendingRainWallEnabled = rainWall.enabled();
        this.pendingRainWallWetness = rainWall.wetness();
        this.pendingRainWallDripDensity = rainWall.dripDensity();
        this.pendingRainWallDripSpeed = rainWall.dripSpeed();
        this.pendingRainWallDripSize = rainWall.dripSize();
        this.pendingRainWallDripBump = rainWall.dripBump();
        this.pendingRainWallDistance = rainWall.distance();

        RTXSettingsConfig.GalaxySettings galaxy =
                RTXSettingsConfig.getGalaxySettings();
        this.pendingGalaxyScale = galaxy.scale();
        this.pendingGalaxyTiltDegrees = galaxy.tiltDegrees();
        this.pendingGalaxyYawDegrees = galaxy.yawDegrees();
        this.pendingGalaxyMix = galaxy.mix();

        RTXSettingsConfig.WaterStyleSettings waterStyle =
                RTXSettingsConfig.getWaterStyleSettings();
        this.pendingWaterStyleWaveScale = waterStyle.waveScale();
        this.pendingWaterStyleWaveSpeed = waterStyle.waveSpeed();
        this.pendingWaterStyleSwellScale = waterStyle.swellScale();
        this.pendingWaterStyleMidScale = waterStyle.midScale();
        this.pendingWaterStyleFineScale = waterStyle.fineScale();
        this.pendingWaterStyleTintMix = waterStyle.tintMix();
        this.pendingWaterStyleDensityScale = waterStyle.densityScale();
        this.pendingWaterStyleCausticStrength = waterStyle.causticStrength();

        this.pendingHDR =
                RTXSettingsConfig.isHDREnabled();

        this.pendingAutoExposureEnabled =
                RTXSettingsConfig.isAutoExposureEnabled();

        this.pendingAutoExposureCompensation =
                RTXSettingsConfig.getAutoExposureCompensation();

        this.pendingFGFrameSync =
                RTXSettingsConfig.isFGFrameSyncEnabled();

        this.pendingFGRefreshCap =
                RTXSettingsConfig.isFGRefreshCapEnabled();

        this.pendingSLDevPlugins =
                RTXSettingsConfig.isSLDevPluginsEnabled();

        this.pendingSerEnabled =
                RTXSettingsConfig.isSerEnabled();

        this.pendingMotionVectors =
                RTXSettingsConfig.getMotionVectorsEnabled();

        this.pendingFGMultiplier =
                RTXSettingsConfig.getFGMultiplier();

        this.pendingFGBaseFrameLimit =
                RTXSettingsConfig.getFGBaseFrameLimit();

        this.pendingArmorMetal =
                RTXSettingsConfig.isEntityArmorMetalEnabled();

        this.pendingCloudFadeFollowsRrMode =
                RTXSettingsConfig.isCloudFadeFollowsRrMode();
        this.pendingCloudPostRrComposite =
                RTXSettingsConfig.isCloudPostRrComposite();
        RTXSettingsConfig.PlaneCloudSettings planeCloud =
                RTXSettingsConfig.getPlaneCloudSettings();
        this.pendingPlaneCloudEnabled = planeCloud.enabled();
        this.pendingPlaneCloudHeightOffset = planeCloud.heightOffset();
        this.pendingPlaneCloudScale = planeCloud.scale();
        this.pendingPlaneCloudSpeed = planeCloud.speed();
        this.pendingPlaneCloudDensity = planeCloud.density();
        this.pendingPlaneCloudCoverage = planeCloud.coverage();
        this.pendingPlaneCloudOctaves = planeCloud.octaves();
        this.pendingPlaneCloudFadeDistance = planeCloud.fadeDistance();
        this.pendingPlaneCloudBrightness = planeCloud.brightness();
        this.pendingCloudResFloorPercent =
                RTXSettingsConfig.getCloudResFloorPercent();
        this.pendingCloudFarStepDensity =
                RTXSettingsConfig.getCloudFarStepDensity();
        this.pendingGiRoulette =
                RTXSettingsConfig.getGiRoulette();
        this.pendingSunMarchFarDistance =
                RTXSettingsConfig.getSunMarchFarDistance();
        this.pendingMotionDebugView =
                RTXSettingsConfig.getMotionDebugView();
        this.pendingDirectLightMode =
                RTXSettingsConfig.getDirectLightMode();
        this.pendingIndirectMode =
                RTXSettingsConfig.getIndirectMode();
        this.pendingSparseRatePercent =
                RTXSettingsConfig.getSparseRatePercent();
    }


    @Override
    protected void addOptions() {

        this.optionRow.clear();

        if(this.page == PAGE_HUB) {
            this.section = PAGE_HUB;
            this.addHubButtons();
            return;
        }

        this.section = PAGE_UPSCALER;

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
                                        "Reflex 低延迟"
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


        this.addOptionRow(
                reflexButton
        );

        CycleButton<Boolean> fgFrameSyncButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingFGFrameSync
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("FG 帧同步"),
                                (button, enabled) ->
                                        this.pendingFGFrameSync = enabled
                        );

        this.addOptionRow(
                fgFrameSyncButton
        );

        final int fgMultiplierMax =
                NativeVulkan.isLoaded()
                        ? NativeVulkan.getFGMultiplierMax()
                        : 2;

        CycleButton<Integer> fgMultiplierButton =
                CycleButton.builder(
                                multiplier -> Component.literal(
                                        multiplier == RTXSettingsConfig.FG_MULTIPLIER_OFF
                                                ? "关"
                                                : multiplier > fgMultiplierMax
                                                        ? multiplier + "x（上限 "
                                                                + fgMultiplierMax + "x）"
                                                        : multiplier + "x"
                                ),
                                this.pendingFGMultiplier
                        )
                        .withValues(RTXSettingsConfig.FG_MULTIPLIER_OFF, 2, 3, 4, 6)
                        .create(
                                Component.literal("帧生成倍增值"),
                                (button, multiplier) ->
                                        this.pendingFGMultiplier = multiplier
                        );

        this.addOptionRow(
                fgMultiplierButton
        );

        final int displayRefreshHz =
                NativeVulkan.isLoaded()
                        ? NativeVulkan.getDisplayRefreshRateHz()
                        : 0;

        final int autoBaseHz =
                displayRefreshHz > 0
                        && this.pendingFGMultiplier > 0
                        ? Math.max(1, displayRefreshHz / this.pendingFGMultiplier)
                        : 0;

        final String reflexWarning =
                this.pendingReflexMode == 0
                        ? "（需 Reflex 开）"
                        : "";

        CycleButton<Integer> fgBaseLimitButton =
                CycleButton.builder(
                                fps -> {
                                    if(fps == RTXSettingsConfig.FG_BASE_LIMIT_AUTO) {
                                        return Component.literal(
                                                autoBaseHz > 0
                                                        ? "自动（" + autoBaseHz + "fps）"
                                                        : "自动"
                                        );
                                    }

                                    if(fps == RTXSettingsConfig.FG_BASE_LIMIT_OFF) {
                                        return Component.literal("关");
                                    }


                                    return Component.literal(
                                            fps + "fps" + reflexWarning
                                    );
                                },
                                this.pendingFGBaseFrameLimit
                        )
                        .withValues(
                                RTXSettingsConfig.FG_BASE_LIMIT_AUTO,
                                RTXSettingsConfig.FG_BASE_LIMIT_OFF,
                                30,
                                41,
                                55,
                                82,
                                120,
                                165,
                                240,
                                300
                        )
                        .create(
                                Component.literal("基础帧率上限"),
                                (button, fps) ->
                                        this.pendingFGBaseFrameLimit = fps
                        );

        this.addOptionRow(
                fgBaseLimitButton
        );

        CycleButton<Boolean> fgRefreshCapButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingFGRefreshCap
                        )
                        .withValues(true, false)
                        .create(
                                Component.literal("按刷新率限制倍率"),
                                (button, enabled) ->
                                        this.pendingFGRefreshCap = enabled
                        );

        this.addOptionRow(
                fgRefreshCapButton
        );

        CycleButton<Boolean> slDevPluginsButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开（重启生效）" : "关"
                                ),
                                this.pendingSLDevPlugins
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("SL 调试 UI（dev 构建）"),
                                (button, enabled) ->
                                        this.pendingSLDevPlugins = enabled
                        );

        this.addOptionRow(
                slDevPluginsButton
        );

        this.flushOptionRow();

        this.section = PAGE_PERF;

        CycleButton<Boolean> serButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开（重启后生效）" : "关（重启后生效）"
                                ),
                                this.pendingSerEnabled
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("SER 二级 GI"),
                                (button, enabled) -> this.pendingSerEnabled = enabled
                        );

        this.addOptionRow(
                serButton
        );

        CycleButton<Boolean> motionVectorsButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingMotionVectors != 0
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("运动矢量（关闭后由 DLSS 自补相机运动）"),
                                (button, enabled) ->
                                        this.pendingMotionVectors = enabled ? 1 : 0
                        );

        this.addOptionRow(
                motionVectorsButton
        );

        this.section = PAGE_MATERIAL;

        CycleButton<Boolean> armorMetalButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingArmorMetal
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("盔甲金属反射"),
                                (button, enabled) -> this.pendingArmorMetal = enabled
                        );

        this.addOptionRow(
                armorMetalButton
        );

        this.section = PAGE_UPSCALER;


        CycleButton<Integer> rrQualityButton =
                CycleButton.builder(
                                RTXSettingsScreen::qualityLabel,
                                this.pendingRRMode
                        )
                        .withValues(
                                RTXSettingsConfig.RR_MODE_PERFORMANCE,
                                RTXSettingsConfig.RR_MODE_BALANCED,
                                RTXSettingsConfig.RR_MODE_QUALITY,
                                RTXSettingsConfig.RR_MODE_DLAA,
                                RTXSettingsConfig.RR_MODE_ULTRA_PERFORMANCE
                        )
                        .create(
                                Component.literal("DLSS RR 光线重构档位"),
                                (button, mode) -> this.pendingRRMode = mode
                        );

        this.addOptionRow(rrQualityButton);
        this.flushOptionRow();

        this.section = PAGE_PERF;

        CycleButton<Integer> giBouncesButton =
                CycleButton.builder(
                                value -> Component.literal(
                                        value == 0 ? "Off" : Integer.toString(value)
                                ),
                                this.pendingGiBounces
                        )
                        .withValues(0, 1, 2, 3, 4)
                        .create(
                                Component.literal("GI 跳数"),
                                (button, value) -> this.pendingGiBounces = value
                        );

        this.addOptionRow(giBouncesButton);

        this.addOptionRow(performanceButton("体积光 ReSTIR", pendingVolumetricRestir,
                List.of(0, 1), value -> pendingVolumetricRestir = value));
        this.addOptionRow(performanceButton("RTAO 近距采样", pendingAoNearSamples,
                List.of(5, 0, 1, 2, 3, 4), value -> pendingAoNearSamples = value));
        this.addOptionRow(performanceButton("RTAO 中距采样", pendingAoMidSamples,
                List.of(0, 1, 2), value -> pendingAoMidSamples = value));
        this.addOptionRow(performanceButton("太阳阴影采样", pendingSunShadowSamples,
                List.of(1, 2), value -> pendingSunShadowSamples = value));
        this.addOptionRow(performanceButton("局部光 RIS 候选", pendingLocalLightRisCandidates,
                List.of(1, 2, 4, 8, 16, 32), value -> pendingLocalLightRisCandidates = value));
        this.addOptionRow(performanceButton("局部光区域采样", pendingLocalLightAreaSamples,
                List.of(1, 2), value -> pendingLocalLightAreaSamples = value));
        this.addOptionRow(performanceButton("水面反射间隔", pendingWaterReflectionInterval,
                List.of(0, 1, 2, 4), value -> pendingWaterReflectionInterval = value));
        this.addOptionRow(performanceButton("光泽全采样距离", pendingGlossyFullDistance,
                List.of(16, 32, 64, 96), value -> pendingGlossyFullDistance = value));
        this.addOptionRow(performanceButton("动态 RTAS 间隔", pendingDynamicRtasInterval,
                List.of(1, 2, 4, 8), value -> pendingDynamicRtasInterval = value));
        this.addOptionRow(performanceButton("RT 粒子上限", pendingMaxRtParticles,
                List.of(0, 64, 128, 256), value -> pendingMaxRtParticles = value));

        CycleButton<Float> rtSppButton =
                CycleButton.builder(
                                value -> Component.literal(
                                        value == Math.floor(value)
                                                ? Integer.toString((int) (float) value) + " spp"
                                                : value + " spp"
                                ),
                                this.pendingRtSpp
                        )
                        .withValues(0.5f, 1.0f, 2.0f, 3.0f, 4.0f)
                        .create(
                                Component.literal("每像素采样数 (spp)"),
                                (button, value) -> this.pendingRtSpp = value
                        );
        this.addOptionRow(rtSppButton);

        this.addOptionRow(performanceButton("GI 俄罗斯轮盘(1=开)", pendingGiRoulette,
                List.of(0, 1), value -> pendingGiRoulette = value));
        this.addOptionRow(performanceButton("太阳透射距离(0=关)", pendingSunMarchFarDistance,
                List.of(0, 8, 16, 32, 64, 128, 256), value -> pendingSunMarchFarDistance = value));
        this.addOptionRow(performanceButton("直接光采样(1=RTXDI复用)", pendingDirectLightMode,
                List.of(0, 1), value -> pendingDirectLightMode = value));
        this.addOptionRow(performanceButton("间接光模式(1=SHaRC,需重启)", pendingIndirectMode,
                List.of(0, 1), value -> pendingIndirectMode = value));
        this.addOptionRow(performanceButton("稀疏渲染采样率%(0=关)", pendingSparseRatePercent,
                List.of(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100),
                value -> pendingSparseRatePercent = value));
        this.addOptionRow(performanceButton("MV 调试图(0=关)", pendingMotionDebugView,
                List.of(0, 1, 2), value -> pendingMotionDebugView = value));

        this.section = PAGE_SKY;

        this.addOptionRow(cloudSlider("太阳圆盘亮度", 0.0, 8.0, 0.01,
                pendingSunDiskLuminance, "%.2f", value -> pendingSunDiskLuminance = (float) value));
        this.addOptionRow(cloudSlider("太阳日冕强度", 0.0, 1.0, 0.01,
                pendingSunHaloStrength, "%.2f", value -> pendingSunHaloStrength = (float) value));
        this.addOptionRow(cloudSlider("太阳角直径（度）", 0.10, 2.00, 0.01,
                pendingSunAngularDiameterDegrees, "%.2f", value -> pendingSunAngularDiameterDegrees = (float) value));
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingPhysicalSkyEnabled)
                .withValues(false, true)
                .create(Component.literal("物理大气散射"),
                        (button, enabled) -> this.pendingPhysicalSkyEnabled = enabled));
        this.addOptionRow(cloudSlider("大气散射强度", 0.0, 2.0, 0.05,
                pendingScatterIntensity, "%.2f", value -> pendingScatterIntensity = (float) value));
        this.addOptionRow(cloudSlider("臭氧吸收强度", 0.0, 2.0, 0.05,
                pendingOzoneStrength, "%.2f", value -> pendingOzoneStrength = (float) value));
        this.addOptionRow(cloudSlider("星空亮度", 0.0, 2.0, 0.05,
                pendingStarIntensity, "%.2f", value -> pendingStarIntensity = (float) value));
        this.addOptionRow(cloudSlider("太阳透射染色", 0.0, 1.0, 0.01,
                pendingSunTintStrength, "%.2f", value -> pendingSunTintStrength = (float) value));
        this.addOptionRow(cloudSlider("极光亮度", 0.0, 3.0, 0.05,
                pendingAuroraBrightness, "%.2f", value -> pendingAuroraBrightness = (float) value));
        this.addOptionRow(cloudSlider("极光流动速度", 0.0, 4.0, 0.05,
                pendingAuroraFlowSpeed, "%.2f", value -> pendingAuroraFlowSpeed = (float) value));
        this.flushOptionRow();
        this.section = PAGE_CLOUD;

        this.addSectionHeader("体积云");

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingCloudEnabled)
                .withValues(false, true)
                .create(Component.literal("体积云"),
                        (button, enabled) -> this.pendingCloudEnabled = enabled));
        this.addOptionRow(CycleButton.builder(
                        value -> Component.literal(switch(value) {
                            case 0 -> "性能";
                            case 2 -> "高质量";
                            default -> "平衡";
                        }),
                        this.pendingCloudQuality)
                .withValues(0, 1, 2)
                .create(Component.literal("体积云质量"),
                        (button, value) -> this.pendingCloudQuality = value));
        final float sizeRef = 0.55f;
        final float sizeSlider = sizeRef / Math.max(pendingCloudSize, 0.05f);
        this.addOptionRow(cloudSlider("云的大小", 0.30, 2.0, 0.05, sizeSlider, "%.2f 倍",
                value -> pendingCloudSize = sizeRef / (float) value));
        this.addOptionRow(cloudSlider("云量（分布密度）", 0.10, 0.90, 0.01,
                pendingCloudCoverage, "%.2f", value -> pendingCloudCoverage = (float) value));
        this.addOptionRow(cloudSlider("云底高度", 96.0, 4000.0, 16.0,
                pendingCloudBottom, "%.0f", value -> pendingCloudBottom = (float) value));
        this.addOptionRow(cloudSlider("云层厚度", 128.0, 5000.0, 16.0,
                pendingCloudThickness, "%.0f", value -> pendingCloudThickness = (float) value));
        this.addOptionRow(cloudSlider("云体密度", 0.25, 5.0, 0.05,
                pendingCloudDensity, "%.2f", value -> pendingCloudDensity = (float) value));
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 外形");
        this.addOptionRow(cloudSlider("竖向拉伸（0扁片↔1高塔）", 0.0, 1.0, 0.01,
                pendingCloudDetailStrength, "%.2f",
                value -> pendingCloudDetailStrength = (float) value));
        this.addOptionRow(cloudSlider("边缘卷曲（0圆润↔1絮状）", 0.0, 0.33, 0.01,
                pendingCloudBaseFeather, "%.2f",
                value -> pendingCloudBaseFeather = (float) value));
        this.addOptionRow(cloudSlider("多次散射强度", 0.0, 1.0, 0.01,
                pendingCloudFarStepDensity, "%.2f",
                value -> pendingCloudFarStepDensity = (float) value));
        this.addOptionRow(cloudSlider("云缘细节尺度", 0.1, 12.0, 0.1,
                pendingCloudDetileStrength, "%.1f（×8）",
                value -> pendingCloudDetileStrength = (float) value));
        this.addOptionRow(cloudSlider("云层竖向铺展（0薄↔1厚）", 0.0, 1.0, 0.01,
                pendingCloudType, "%.2f", value -> pendingCloudType = (float) value));
        this.addOptionRow(cloudSlider("云缘侵蚀幅度", 0.0, 2.0, 0.05,
                pendingCloudErosionScale, "%.2f",
                value -> pendingCloudErosionScale = (float) value));
        this.addOptionRow(cloudSlider("云距（硬上限）", 2000.0, 200000.0, 1000.0,
                pendingCloudViewDistance, "%.0f 格",
                value -> pendingCloudViewDistance = (float) value));
        this.addOptionRow(cloudSlider("远处淡出起点", 0.0, 0.99, 0.01,
                pendingCloudWeatherScale, "%.2f",
                value -> pendingCloudWeatherScale = (float) value));
        this.addOptionRow(cloudSlider("逆光银边", 0.0, 1.0, 0.01,
                pendingCloudSilver, "%.2f",
                value -> pendingCloudSilver = (float) value));
        this.flushOptionRow();

        this.addSectionHeader("体积云 · HP 全参数（v5）");
        float[] hpValues = RTXSettingsConfig.getCloudHpSettings().toParamArray();
        for(int hpIndex = 0; hpIndex < RTXSettingsConfig.CLOUD_HP_LABELS.length; ++hpIndex)
        {
            final int hpSlot = hpIndex;
            final float[] hpRange = RTXSettingsConfig.CLOUD_HP_RANGES[hpSlot];
            this.addOptionRow(cloudSlider(RTXSettingsConfig.CLOUD_HP_LABELS[hpSlot],
                    hpRange[0], hpRange[1], hpRange[2], hpValues[hpSlot], "%.3f",
                    value -> {
                        float[] next = RTXSettingsConfig.getCloudHpSettings().toParamArray();
                        next[hpSlot] = (float) value;
                        RTXSettingsConfig.CloudHpSettings updated =
                                new RTXSettingsConfig.CloudHpSettings(next);
                        if(!NativeVulkan.isLoaded()
                                || NativeVulkan.setCloudHpParams(updated.toParamArray())) {
                            RTXSettingsConfig.setCloudHpSettings(updated);
                        }
                    }));
        }
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 步进（性能 / 画质）");
        this.addOptionRow(cloudSlider("光线步进（0=跟随质量档）", 0.0, 160.0, 8.0,
                pendingCloudSunReddening, "%.0f 步",
                value -> pendingCloudSunReddening = (float) value));
        this.addOptionRow(cloudSlider("阴影步进（0=跟随质量档）", 0.0, 32.0, 1.0,
                pendingCloudTopStretch, "%.0f 步",
                value -> pendingCloudTopStretch = (float) value));
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 受光");
        this.addOptionRow(cloudSlider("太阳照云强度", 0.0, 3.0, 0.01,
                pendingCloudSunlightStrength, "%.2f", value -> pendingCloudSunlightStrength = (float) value));
        this.addOptionRow(cloudSlider("天光强度", 0.0, 3.0, 0.05,
                pendingPowderStrength, "%.2f", value -> pendingPowderStrength = (float) value));
        this.addOptionRow(cloudSlider("月光强度", -1, 3, 0.05,
                moonStrength, "%.2f", value -> moonStrength = (float)value));
        this.addOptionRow(cloudSlider("月光相位", -1, 1, 0.05,
                moonPhase, "%.2f", value -> moonPhase = (float)value));
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 多次散射 / 能量");

        for(int i = 0; i < 4; i++) {

            this.addOptionRow(
                    this.makeUeCloudSlider(
                            i,
                            this.UE_CLOUD_LABELS[i],
                            this.UE_CLOUD_MIN[i],
                            this.UE_CLOUD_MAX[i],
                            this.UE_CLOUD_STEP[i]
                    )
            );

            if(i % 2 == 1) {
                this.flushOptionRow();
            }
        }

        this.addSectionHeader("体积云 · 时域");
        this.addOptionRow(CycleButton.builder(
                enabled -> Component.literal(enabled ? "开" : "关"), msMeanReproject)
                .withValues(false, true).create(Component.literal("软边时域重投影"),
                        (button, value) -> msMeanReproject = value));
        this.flushOptionRow();


        this.addSectionHeader("大气透视");
        this.addOptionRow(cloudSlider("云层大气透视", 0, 2, 0.05,
                cloudAerialStrength, "%.2f", value -> cloudAerialStrength = (float) value));
        this.addOptionRow(cloudSlider("地面大气透视强度", 0, 2, 0.05,
                groundAerialStrength, "%.2f", value -> groundAerialStrength = (float) value));
        this.addOptionRow(cloudSlider("地面遮挡衰减", 0, 1, 0.05,
                groundAerialOcclusion, "%.2f", value -> groundAerialOcclusion = (float) value));
        this.flushOptionRow();

        this.section = PAGE_FOG;

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingFogEnabled)
                .withValues(false, true)
                .create(Component.literal("体积雾"),
                        (button, enabled) -> this.pendingFogEnabled = enabled));
        this.addOptionRow(cloudSlider("雾密度基准 (VFOG_DENSITY_BASE)", 0.00, 100.00, 0.50,
                pendingFogDensity, "%.2f", value -> pendingFogDensity = (float) value));
        this.addOptionRow(cloudSlider("雾高度上界 (VFOG_HEIGHT)", -64, 320, 1,
                pendingFogHeight, "%.0f 格", value -> pendingFogHeight = (float) value));
        this.addOptionRow(cloudSlider("雾高度下界 (VFOG_HEIGHT_2)", -64, 320, 1,
                pendingFogScaleHeight, "%.0f 格", value -> pendingFogScaleHeight = (float) value));
        this.addOptionRow(cloudSlider("太阳散射强度", 0.00, 4.00, 0.05,
                pendingFogSunStrength, "%.2f", value -> pendingFogSunStrength = (float) value));
        this.addOptionRow(cloudSlider("环境散射强度", 0.00, 4.00, 0.05,
                pendingFogAmbientStrength, "%.2f", value -> pendingFogAmbientStrength = (float) value));
        this.addOptionRow(cloudSlider("散射各向异性 g", 0.00, 0.90, 0.05,
                pendingFogAnisotropy, "%.2f", value -> pendingFogAnisotropy = (float) value));
        this.addOptionRow(cloudSlider("雨天雾增强", 0.0, 8.0, 0.25,
                pendingFogRainBoost, "%.2f 倍", value -> pendingFogRainBoost = (float) value));
        this.addOptionRow(cloudSlider("雾最远作用距离", 64, 1024, 32,
                pendingFogMaxDistance, "%.0f 格", value -> pendingFogMaxDistance = (float) value));
        this.addOptionRow(cloudSlider("近处留白距离", 0, 64, 2,
                pendingFogStartDistance, "%.0f 格", value -> pendingFogStartDistance = (float) value));
        this.addOptionRow(cloudSlider("太阳取样点（性能）", 1, 8, 1,
                pendingFogSunSteps, "%.0f", value -> pendingFogSunSteps = (int) Math.round(value)));
        this.flushOptionRow();

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingFogWispEnabled)
                .withValues(false, true)
                .create(Component.literal("雨天地面雾浪"),
                        (button, enabled) -> this.pendingFogWispEnabled = enabled));
        this.addOptionRow(cloudSlider("雾浪强度", 0.0, 4.0, 0.05,
                pendingFogWispStrength, "%.2f",
                value -> pendingFogWispStrength = (float) value));
        this.addOptionRow(cloudSlider("雾浪尺度（波长）", 32, 1024, 4,
                pendingFogWispWavelength, "%.0f 格",
                value -> pendingFogWispWavelength = (float) value));
        this.addOptionRow(cloudSlider("雾浪漂移速度", 0.0, 4.0, 0.05,
                pendingFogWispSpeed, "%.2f 格/秒",
                value -> pendingFogWispSpeed = (float) value));
        this.flushOptionRow();

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingGodRayEnabled)
                .withValues(false, true)
                .create(Component.literal("水下体积光"),
                        (button, enabled) -> this.pendingGodRayEnabled = enabled));
        this.addOptionRow(cloudSlider("体积光采样步数（性能）", 4, 48, 2,
                pendingGodRaySteps, "%.0f", value -> pendingGodRaySteps = (int) Math.round(value)));
        this.addOptionRow(cloudSlider("体积光云影采样（性能）", 1, 6, 1,
                pendingGodRayOcclusionSamples, "%.0f", value -> pendingGodRayOcclusionSamples = (int) Math.round(value)));
        this.addOptionRow(cloudSlider("体积光强度", 0.0, 3.0, 0.05,
                pendingGodRayIntensity, "%.2f", value -> pendingGodRayIntensity = (float) value));
        this.addOptionRow(cloudSlider("体积光时间重投影", 0.00, 0.95, 0.05,
                pendingGodRayReprojection, "%.2f",
                value -> pendingGodRayReprojection = (float) value));
        this.flushOptionRow();

        this.section = PAGE_RAIN;

        this.addOptionRow(cloudSlider("雨线粗细", 1.0, 40.0, 0.1,
                pendingRainWidth, "%.1f 毫米", value -> pendingRainWidth = (float) value));
        this.addOptionRow(cloudSlider("雨线长度", 0.4, 8.0, 0.1,
                pendingRainStreakLength, "%.1f 格", value -> pendingRainStreakLength = (float) value));
        this.addOptionRow(cloudSlider("雨线浓度", 0.05, 1.0, 0.01,
                pendingRainOpacity, "%.2f", value -> pendingRainOpacity = (float) value));
        this.addOptionRow(cloudSlider("雨线数量（性能）", 0.1, 2.0, 0.05,
                pendingRainDensityScale, "%.2f 倍",
                value -> pendingRainDensityScale = (float) value));
        this.addOptionRow(cloudSlider("雨线亮度", 0.05, 3.0, 0.01,
                pendingRainBrightness, "%.2f 倍", value -> pendingRainBrightness = (float) value));

        this.addOptionRow(cloudSlider("雪密度", 0.25, 4.0, 0.05,
                pendingSnowDensity, "%.2f 倍", value -> pendingSnowDensity = (float) value));
        this.addOptionRow(cloudSlider("雪片大小", 0.25, 4.0, 0.05,
                pendingSnowSize, "%.2f 倍", value -> pendingSnowSize = (float) value));
        this.addOptionRow(cloudSlider("雪浓度", 0.05, 1.0, 0.01,
                pendingSnowOpacity, "%.2f", value -> pendingSnowOpacity = (float) value));
        this.addOptionRow(cloudSlider("雪亮度", 0.05, 3.0, 0.01,
                pendingSnowBrightness, "%.2f 倍", value -> pendingSnowBrightness = (float) value));
        this.addOptionRow(cloudSlider("雪下落速度", 0.25, 4.0, 0.05,
                pendingSnowFallSpeed, "%.2f 倍", value -> pendingSnowFallSpeed = (float) value));
        this.addOptionRow(cloudSlider("雪摇摆幅度", 0.0, 1.0, 0.05,
                pendingSnowSway, "%.2f 格", value -> pendingSnowSway = (float) value));

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingRainWallEnabled)
                .withValues(false, true)
                .create(Component.literal("墙面雨痕"),
                        (button, enabled) -> this.pendingRainWallEnabled = enabled));
        this.addOptionRow(cloudSlider("墙面湿度", 0.0, 2.0, 0.05,
                pendingRainWallWetness, "%.2f 倍", value -> pendingRainWallWetness = (float) value));
        this.addOptionRow(cloudSlider("墙面水滴密度", 0.0, 1.0, 0.01,
                pendingRainWallDripDensity, "%.2f", value -> pendingRainWallDripDensity = (float) value));
        this.addOptionRow(cloudSlider("墙面水流速度", 0.1, 4.0, 0.05,
                pendingRainWallDripSpeed, "%.2f 倍", value -> pendingRainWallDripSpeed = (float) value));
        this.addOptionRow(cloudSlider("雨滴大小", 0.25, 4.0, 0.05,
                pendingRainWallDripSize, "%.2f 倍", value -> pendingRainWallDripSize = (float) value));
        this.addOptionRow(cloudSlider("墙面水滴凸起", 0.0, 2.0, 0.05,
                pendingRainWallDripBump, "%.2f 倍", value -> pendingRainWallDripBump = (float) value));
        this.addOptionRow(cloudSlider("墙面雨范围（性能）", 16, 160, 8,
                pendingRainWallDistance, "%.0f 格", value -> pendingRainWallDistance = (float) value));
        this.flushOptionRow();

        this.section = PAGE_WATER;

        this.addOptionRow(cloudSlider("波浪强度", 0.0, 2.0, 0.05,
                pendingWaterStyleWaveScale, "%.2f 倍", value -> pendingWaterStyleWaveScale = (float) value));
        this.addOptionRow(cloudSlider("波速", 0.0, 3.0, 0.05,
                pendingWaterStyleWaveSpeed, "%.2f 倍", value -> pendingWaterStyleWaveSpeed = (float) value));
        this.addOptionRow(cloudSlider("大涌起伏", 0.0, 2.0, 0.05,
                pendingWaterStyleSwellScale, "%.2f 倍", value -> pendingWaterStyleSwellScale = (float) value));
        this.addOptionRow(cloudSlider("细波纹理", 0.0, 2.0, 0.05,
                pendingWaterStyleMidScale, "%.2f 倍", value -> pendingWaterStyleMidScale = (float) value));
        this.addOptionRow(cloudSlider("碎浪颗粒", 0.0, 2.0, 0.05,
                pendingWaterStyleFineScale, "%.2f 倍", value -> pendingWaterStyleFineScale = (float) value));
        this.addOptionRow(cloudSlider("青绿水色", 0.0, 1.0, 0.01,
                pendingWaterStyleTintMix, "%.2f", value -> pendingWaterStyleTintMix = (float) value));
        this.addOptionRow(cloudSlider("水体浓度", 0.25, 4.0, 0.05,
                pendingWaterStyleDensityScale, "%.2f 倍", value -> pendingWaterStyleDensityScale = (float) value));
        this.addOptionRow(cloudSlider("波峰透光强度", 0, 2, 0.05,
                wpCrestBacklit, "%.2f", value -> wpCrestBacklit = (float)value));
        this.addOptionRow(cloudSlider("焦散色散", 0, 1, 0.01,
                wpCausticDispersion, "%.2f", value -> wpCausticDispersion = (float)value));
        this.addOptionRow(cloudSlider("焦散水深弥散", 0, 1, 0.01,
                wpCausticDepthDiffusion, "%.2f", value -> wpCausticDepthDiffusion = (float)value));
        this.addOptionRow(CycleButton.builder(
                enabled -> Component.literal(enabled ? "开" : "关"), wpPhysicalPhase)
                .withValues(false, true).create(Component.literal("水下物理相位"),
                        (button, value) -> wpPhysicalPhase = value));
        this.addOptionRow(cloudSlider("焦散亮度", 0.0, 4.0, 0.05,
                pendingWaterStyleCausticStrength, "%.2f", value -> pendingWaterStyleCausticStrength = (float) value));
        this.addOptionRow(cloudSlider("反射焦散强度", 0.0, 8.0, 0.05,
                waterReflectCausticStrength, "%.2f", value -> waterReflectCausticStrength = (float) value));

        this.addOptionRow(cloudSlider("交互波标定", 0.0, 1.0, 0.05,
                waterWaveCalibration, "%.2f", value -> waterWaveCalibration = (float)value));
        this.addOptionRow(cloudSlider("交互波强度", 0.0, 2.0, 0.05,
                waterWaveStrength, "%.2f 倍", value -> waterWaveStrength = (float)value));
        this.addOptionRow(cloudSlider("波面混合", 0.0, 1.0, 0.05,
                waterWaveBlend, "%.2f", value -> waterWaveBlend = (float)value));
        this.addOptionRow(cloudSlider("波场海绵层", 0.0, 0.45, 0.01,
                waterSimSpongeWidth, "%.2f", value -> waterSimSpongeWidth = (float)value));
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "高斯凸起" : "同心环"),
                        waterSimGaussianSource > 0.5f)
                .withValues(false, true)
                .create(Component.literal("波源形状"),
                        (button, enabled) -> waterSimGaussianSource = enabled ? 1.0f : 0.0f));
        this.addOptionRow(cloudSlider("波场阻尼", 0.0, 3.0, 0.05,
                waterSimDampingScale, "%.2f 倍", value -> waterSimDampingScale = (float)value));
        this.addOptionRow(cloudSlider("波场传播速度", 0.0, 2.0, 0.05,
                waterSimPropagationSpeed, "%.2f 倍", value -> waterSimPropagationSpeed = (float)value));
        this.addOptionRow(cloudSlider("薄层SSS强度", 0.0, 2.0, 0.05,
                waterSssStrength, "%.2f 倍", value -> waterSssStrength = (float)value));
        this.addOptionRow(cloudSlider("SSS非线性光程", 0.0, 1.0, 0.05,
                waterSssNonlinear, "%.2f", value -> waterSssNonlinear = (float)value));
        this.addOptionRow(cloudSlider("SSS能量补偿", 0.0, 4.0, 0.1,
                waterSssBoost, "%.2f", value -> waterSssBoost = (float)value));
        this.addOptionRow(cloudSlider("SSS相位g", 0.0, 0.95, 0.05,
                waterSssPhaseG, "%.2f", value -> waterSssPhaseG = (float)value));

        this.addOptionRow(cloudSlider("风源波纹尺度", 0.35, 3.0, 0.05,
        waterWaveLengthScale, "%.2f 倍", value -> waterWaveLengthScale = (float)value));
        this.addOptionRow(cloudSlider("波纹方向", 0.0, 355.0, 5.0,
        waterWaveDirection, "%.0f 度", value -> waterWaveDirection = (float)value));
        this.addOptionRow(cloudSlider("波纹幅度", 0.0, 2.0, 0.05,
        waterWaveCrestSharpness, "%.2f 倍", value -> waterWaveCrestSharpness = (float)value));
        this.addOptionRow(cloudSlider("波峰不规则度", 0.0, 2.0, 0.05,
        waterWaveWarpStrength, "%.2f 倍", value -> waterWaveWarpStrength = (float)value));
        this.flushOptionRow();

        this.addOptionRow(cloudSlider("最长波长", 2.0, 8.0, 0.1,
        waterWaveAflScale, "%.1f 格", value -> waterWaveAflScale = (float)value));
        this.addOptionRow(cloudSlider("波数", 1.0, 12.0, 1.0,
        waterWaveAflLayers, "%.0f 根", value -> waterWaveAflLayers = (float)value));
        this.flushOptionRow();

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.waterFftEnabled)
                .withValues(false, true)
                .create(Component.literal("海浪"),
                        (button, enabled) -> this.waterFftEnabled = enabled));
        this.addOptionRow(cloudSlider("振幅", 0.0, 3.0, 0.05,
        waterFftAmplitude, "%.2f 倍", value -> waterFftAmplitude = (float)value));
        this.addOptionRow(cloudSlider("平铺尺度", 2.0, 128.0, 1.0,
        waterFftTilePeriod, "%.0f 格", value -> waterFftTilePeriod = (float)value));
        this.addOptionRow(cloudSlider("波峰锐化", 0.0, 3.0, 0.05,
        waterFftChoppiness, "%.2f", value -> waterFftChoppiness = (float)value));
        this.addOptionRow(cloudSlider("波浪细节", 0.0, 3.0, 0.05,
        waterFftDetail, "%.2f 倍", value -> waterFftDetail = (float)value));
        this.flushOptionRow();

        this.section = PAGE_POST;

        this.addOptionRow(CycleButton.builder(
                        mode -> postTonemapLabel(mode), this.postTonemapMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(Component.literal("色彩映射"),
                        (button, value) -> this.postTonemapMode = value));
        this.addOptionRow(cloudSlider("调色 R 增益", 0.25, 2.5, 0.01,
        postGainR, "%.2f", value -> postGainR = (float)value));
        this.addOptionRow(cloudSlider("调色 G 增益", 0.25, 2.5, 0.01,
        postGainG, "%.2f", value -> postGainG = (float)value));
        this.addOptionRow(cloudSlider("调色 B 增益", 0.25, 2.5, 0.01,
        postGainB, "%.2f", value -> postGainB = (float)value));
        this.addOptionRow(cloudSlider("自动曝光强度", 0.0, 1.0, 0.05,
        postExposureStrength, "%.2f", value -> postExposureStrength = (float)value));
        this.addOptionRow(cloudSlider("对比度", 0.5, 2.0, 0.05,
        postContrast, "%.2f 倍", value -> postContrast = (float)value));
        this.addOptionRow(cloudSlider("饱和度", 0.0, 2.0, 0.05,
        postSaturation, "%.2f 倍", value -> postSaturation = (float)value));
        this.addOptionRow(cloudSlider("Gamma", 1.8, 2.6, 0.02,
        postGamma, "%.2f", value -> postGamma = (float)value));
        this.flushOptionRow();

        this.addOptionRow(cloudSlider("泛光强度", 0.0, 2.0, 0.05,
        postBloomStrength, "%.2f (0=关)", value -> postBloomStrength = (float)value));
        this.addOptionRow(cloudSlider("TLAS剔除距离", 0, 320, 32,
        postTlasCullDistance, "%.0f 格 (0=自适应)", value -> postTlasCullDistance = (int)(float)value));
        this.addOptionRow(cloudSlider("TLAS视锥剔除边距", 0, 96, 8,
        postTlasFrustumCullMargin, "%.0f 格 (0=关)", value -> postTlasFrustumCullMargin = (int)(float)value));
        this.addOptionRow(cloudSlider("远云RR引导距离", 0, 20000, 250,
        postRrFarCloudDistance, "%.0f 格 (0=关)", value -> postRrFarCloudDistance = (int)(float)value));
        this.addOptionRow(cloudSlider("云分辨率下限", 0, 100, 5,
        pendingCloudResFloorPercent, "%.0f%%输出 (0=关)",
        value -> pendingCloudResFloorPercent = (int)(float)value));
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingCloudPostRrComposite)
                .withValues(false, true)
                .create(Component.literal("远云脱离RR"),
                        (button, enabled) -> this.pendingCloudPostRrComposite = enabled));
        this.flushOptionRow();

        this.flushOptionRow();
        if(this.section == this.page) {
            this.list.addBig(
                    Button.builder(
                                    Component.literal("▸ 色彩分级 · 详细参数（独立页面）"),
                                    button -> this.openPage(PAGE_COLORGRADE))
                            .width(310)
                            .build());
        }

        this.section = PAGE_COLORGRADE;

        this.addSectionHeader("预设（只改分级；色彩映射请在后处理页选，推荐 AgX）");
        this.addOptionRow(Button.builder(
                        Component.literal("预设：冰岛 · 冷寂"),
                        button -> this.applyColorGradePreset(PRESET_ICELAND))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：格陵兰 · 极夜"),
                        button -> this.applyColorGradePreset(PRESET_GREENLAND))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：晨雾 · 苍灰"),
                        button -> this.applyColorGradePreset(PRESET_MIST))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：复位（中性）"),
                        button -> this.applyColorGradePreset(PRESET_NEUTRAL))
                .width(150).build());
        this.flushOptionRow();

        float[] cgValues = RTXSettingsConfig.getColorGradeSettings().toParamArray();
        for(int cgIndex = 0; cgIndex < RTXSettingsConfig.COLOR_GRADE_LABELS.length; ++cgIndex)
        {
            if(cgIndex == 0)
            {
                this.addSectionHeader("① 曝光与黑白场");
            }
            if(cgIndex == 5)
            {
                this.addSectionHeader("② 逐通道 Lift / Gamma / Gain（ASC-CDL）");
            }
            if(cgIndex == 14)
            {
                this.addSectionHeader("③ 分离色调（暗部 / 亮部各推一个色相）");
            }
            if(cgIndex == 18)
            {
                this.addSectionHeader("④ 氛围（饱和度 / 褪色 / 暗角 / 色相）");
            }

            final int cgSlot = cgIndex;
            final float[] cgRange = RTXSettingsConfig.COLOR_GRADE_RANGES[cgSlot];
            final String cgFormat =
                    cgRange[2] >= 1.0f ? "%.0f"
                            : (cgRange[2] >= 0.01f ? "%.2f" : "%.3f");
            this.addOptionRow(cloudSlider(RTXSettingsConfig.COLOR_GRADE_LABELS[cgSlot],
                    cgRange[0], cgRange[1], cgRange[2], cgValues[cgSlot], cgFormat,
                    value -> {
                        float[] next = RTXSettingsConfig.getColorGradeSettings().toParamArray();
                        next[cgSlot] = (float) value;
                        RTXSettingsConfig.ColorGradeSettings updated =
                                new RTXSettingsConfig.ColorGradeSettings(next);
                        if(!NativeVulkan.isLoaded()
                                || NativeVulkan.setColorGradeParams(updated.toParamArray())) {
                            RTXSettingsConfig.setColorGradeSettings(updated);
                        }
                    }));
        }
        this.flushOptionRow();

        this.section = PAGE_MATERIAL;

        this.addOptionRow(cloudSlider("镜面弹射层数", 1, 8, 1,
        postMirrorBounceCount, "%.0f 层", value -> postMirrorBounceCount = (int)(float)value));


        AbstractSliderButton ambientBrightnessSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingNoDirectLightAmbient
                ) {

                    {
                        this.updateMessage();
                    }


                    @Override
                    protected void updateMessage() {

                        double snapped =
                                Math.round(
                                        this.value * 10000.0
                                ) / 10000.0;


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "无直射光环境亮度: %.4f",
                                                snapped
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {

                        double snapped =
                                Math.round(
                                        this.value * 10000.0
                                ) / 10000.0;


                        this.value =
                                snapped;


                        RTXSettingsScreen.this.pendingNoDirectLightAmbient =
                                (float) snapped;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                ambientBrightnessSlider
        );


        AbstractSliderButton pomDepthSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingPOMDepth / 0.20
                ) {

                    {
                        this.updateMessage();
                    }


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


        this.addOptionRow(
                pomDepthSlider
        );


        CycleButton<Boolean> pomSamplingButton =
                CycleButton.builder(
                                sharp -> Component.literal(
                                        sharp ? "锐利（单纹素）" : "平滑（双线性）"
                                ),
                                this.pendingPOMSharp
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("视差采样"),
                                (button, sharp) -> this.pendingPOMSharp = sharp
                        );

        this.addOptionRow(
                pomSamplingButton
        );


        CycleButton<Integer> giNearRateButton =
                CycleButton.builder(
                                level -> Component.literal(
                                        level == 0 ? "全速"
                                                : (level == 1 ? "半速" : "四分之一")
                                ),
                                this.pendingGiNearRate
                        )
                        .withValues(0, 1, 2)
                        .create(
                                Component.literal("GI 近场采样"),
                                (button, level) -> this.pendingGiNearRate = level
                        );

        this.addOptionRow(
                giNearRateButton
        );


        CycleButton<Boolean> glossyRoughLodButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "按粗糙度降速" : "全速"
                                ),
                                this.pendingGlossyRoughLod
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("光泽采样"),
                                (button, enabled) -> this.pendingGlossyRoughLod = enabled
                        );

        this.addOptionRow(
                glossyRoughLodButton
        );


        CycleButton<Boolean> subsurfaceButton =
                CycleButton.builder(
                                trueSss -> Component.literal(
                                        trueSss
                                                ? "真次表面散射（天光透射）"
                                                : "太阳背光（仅直射）"
                                ),
                                this.pendingTrueSubsurfaceScattering
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("树叶次表面散射"),
                                (button, trueSss) ->
                                        this.pendingTrueSubsurfaceScattering = trueSss
                        );

        this.addOptionRow(
                subsurfaceButton
        );


        AbstractSliderButton localLightDistanceSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        (this.pendingLocalLightMaxDistance - 4.0) / 26.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedDistance() {
                        return Math.round(
                                this.value * 26.0 + 4.0
                        );
                    }


                    @Override
                    protected void updateMessage() {
                        double dist =
                                this.snappedDistance();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "本地光最远照明距离: %d 格",
                                                (int) dist
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double dist =
                                this.snappedDistance();


                        this.value =
                                (dist - 4.0) / 26.0;


                        RTXSettingsScreen.this.pendingLocalLightMaxDistance =
                                (float) dist;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                localLightDistanceSlider
        );


        AbstractSliderButton localLightIntensitySlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingLocalLightIntensity / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedIntensity() {
                        return Math.round(
                                this.value * 80.0
                        ) / 20.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double intensity =
                                this.snappedIntensity();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "本地光源照射强度: %.2fx",
                                                intensity
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double intensity =
                                this.snappedIntensity();


                        this.value =
                                intensity / 4.0;


                        RTXSettingsScreen.this.pendingLocalLightIntensity =
                                (float) intensity;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                localLightIntensitySlider
        );

        final AbstractSliderButton dynamicLightIntensitySlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingDynamicLightIntensity / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedIntensity() {
                        return Math.round(
                                this.value * 80.0
                        ) / 20.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double intensity =
                                this.snappedIntensity();


                        this.setMessage(
                                Component.literal(
                                        intensity <= 0.0
                                                ? "移动光源: 关"
                                                : String.format(
                                                        java.util.Locale.ROOT,
                                                        "移动光源强度: %.2fx",
                                                        intensity
                                                )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double intensity =
                                this.snappedIntensity();


                        this.value =
                                intensity / 4.0;


                        RTXSettingsScreen.this.pendingDynamicLightIntensity =
                                (float) intensity;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                dynamicLightIntensitySlider
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


        this.addOptionRow(
                hdrButton
        );


        CycleButton<Boolean> autoExposureButton =
                CycleButton.builder(
                                enabled ->
                                        Component.literal(
                                                enabled
                                                        ? "On"
                                                        : "Off"
                                        ),
                                this.pendingAutoExposureEnabled
                        )
                        .withValues(
                                false,
                                true
                        )
                        .create(
                                Component.literal(
                                        "自动曝光"
                                ),
                                (button, enabled) ->
                                        this.pendingAutoExposureEnabled =
                                                enabled
                        );


        this.addOptionRow(
                autoExposureButton
        );


        this.flushOptionRow();


        AbstractSliderButton exposureCompSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        (this.pendingAutoExposureCompensation + 2.0) / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedEV() {
                        return Math.round(
                                (this.value * 4.0 - 2.0) *
                                        10.0
                        ) / 10.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double ev =
                                this.snappedEV();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "曝光补偿: %+.1f EV",
                                                ev
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double ev =
                                this.snappedEV();


                        this.value =
                                (ev + 2.0) / 4.0;


                        RTXSettingsScreen.this.pendingAutoExposureCompensation =
                                (float) ev;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                exposureCompSlider
        );
        this.flushOptionRow();
    }


    private void applyPendingSettings() {

        boolean allApplied =
                true;



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

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGFrameSync(
                        this.pendingFGFrameSync
                ))
        {
            RTXSettingsConfig.setFGFrameSyncEnabled(
                    this.pendingFGFrameSync
            );
        }
        else
        {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGRefreshCapEnabled(
                        this.pendingFGRefreshCap
                ))
        {
            RTXSettingsConfig.setFGRefreshCapEnabled(
                    this.pendingFGRefreshCap
            );
        }
        else
        {
            allApplied = false;
        }

        RTXSettingsConfig.setSLDevPluginsEnabled(
                this.pendingSLDevPlugins
        );

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGMultiplier(
                        this.pendingFGMultiplier
                ))
        {
            RTXSettingsConfig.setFGMultiplier(
                    this.pendingFGMultiplier
            );
        }
        else
        {
            this.pendingFGMultiplier =
                    RTXSettingsConfig.getFGMultiplier();

            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGBaseFrameLimit(
                        this.pendingFGBaseFrameLimit
                ))
        {
            RTXSettingsConfig.setFGBaseFrameLimit(
                    this.pendingFGBaseFrameLimit
            );
        }
        else
        {
            this.pendingFGBaseFrameLimit =
                    RTXSettingsConfig.getFGBaseFrameLimit();

            allApplied = false;
        }

        RTXSettingsConfig.setSerEnabled(
                this.pendingSerEnabled
        );

        if(!NativeVulkan.isLoaded())
        {
            RTXSettingsConfig.setMotionVectorsEnabled(
                    this.pendingMotionVectors
            );
        }
        else
        {
            this.pendingMotionVectors =
                    NativeVulkan.setMotionVectorScaleMode(
                            this.pendingMotionVectors
                    );

            RTXSettingsConfig.setMotionVectorsEnabled(
                    this.pendingMotionVectors
            );
        }

        RTXSettingsConfig.setEntityArmorMetalEnabled(
                this.pendingArmorMetal
        );



        if(
                !NativeVulkan.isLoaded() ||
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

        final boolean upscalerApplied =
                !NativeVulkan.isLoaded() ||
                        NativeVulkan.setUpscalerMode(RTXSettingsConfig.UPSCALER_MODE_RR);

        if(upscalerApplied)
        {
            RTXSettingsConfig.setUpscalerMode(RTXSettingsConfig.UPSCALER_MODE_RR);
        }
        else
        {
            allApplied = false;
        }

        RTXSettingsConfig.PerformanceSettings updatedPerformance =
                new RTXSettingsConfig.PerformanceSettings(
                        this.pendingAoNearSamples,
                        this.pendingAoMidSamples,
                        this.pendingGiBounces,
                        this.pendingSunShadowSamples,
                        this.pendingLocalLightRisCandidates,
                        this.pendingLocalLightAreaSamples,
                        this.pendingWaterReflectionInterval,
                        this.pendingGlossyFullDistance,
                        this.pendingDynamicRtasInterval,
                        this.pendingMaxRtParticles,
                        this.pendingRtSpp,
                        this.pendingVolumetricRestir
                );
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setPerformanceSettings(updatedPerformance))
        {
            RTXSettingsConfig.setPerformanceSettings(updatedPerformance);
        }
        else
        {
            allApplied = false;
        }

        RTXSettingsConfig.setRainSettings(
                new RTXSettingsConfig.RainSettings(
                        this.pendingRainOpacity,
                        this.pendingRainStreakLength,
                        this.pendingRainBrightness,
                        this.pendingRainWidth,
                        this.pendingRainDensityScale
                )
        );

        RTXSettingsConfig.setSnowSettings(
                new RTXSettingsConfig.SnowSettings(
                        this.pendingSnowSize,
                        this.pendingSnowDensity,
                        this.pendingSnowOpacity,
                        this.pendingSnowBrightness,
                        this.pendingSnowFallSpeed,
                        this.pendingSnowSway
                )
        );

        RTXSettingsConfig.RainWallSettings updatedRainWall =
                new RTXSettingsConfig.RainWallSettings(
                        this.pendingRainWallEnabled,
                        this.pendingRainWallWetness,
                        this.pendingRainWallDripDensity,
                        this.pendingRainWallDripSpeed,
                        this.pendingRainWallDripSize,
                        this.pendingRainWallDripBump,
                        this.pendingRainWallDistance
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setRainWallSettings(
                updatedRainWall.enabled(),
                updatedRainWall.wetness(),
                updatedRainWall.dripDensity(),
                updatedRainWall.dripSpeed(),
                updatedRainWall.dripSize(),
                updatedRainWall.dripBump(),
                updatedRainWall.distance()
        )) {
            RTXSettingsConfig.setRainWallSettings(updatedRainWall);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.GalaxySettings updatedGalaxy =
                new RTXSettingsConfig.GalaxySettings(
                        this.pendingGalaxyScale,
                        this.pendingGalaxyTiltDegrees,
                        this.pendingGalaxyYawDegrees,
                        this.pendingGalaxyMix
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setGalaxySettings(
                updatedGalaxy.scale(),
                updatedGalaxy.tiltDegrees(),
                updatedGalaxy.yawDegrees(),
                updatedGalaxy.mix()
        )) {
            RTXSettingsConfig.setGalaxySettings(updatedGalaxy);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.WaterStyleSettings updatedWaterStyle =
                new RTXSettingsConfig.WaterStyleSettings(
                        this.pendingWaterStyleWaveScale,
                        this.pendingWaterStyleTintMix,
                        this.pendingWaterStyleDensityScale,
                        this.pendingWaterStyleCausticStrength,
                        this.pendingWaterStyleWaveSpeed,
                        this.pendingWaterStyleSwellScale,
                        this.pendingWaterStyleMidScale,
                        this.pendingWaterStyleFineScale
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterStyleSettings(
                updatedWaterStyle.waveScale(),
                updatedWaterStyle.tintMix(),
                updatedWaterStyle.densityScale(),
                updatedWaterStyle.causticStrength(),
                updatedWaterStyle.waveSpeed(),
                updatedWaterStyle.swellScale(),
                updatedWaterStyle.midScale(),
                updatedWaterStyle.fineScale()
        )) {
            RTXSettingsConfig.setWaterStyleSettings(updatedWaterStyle);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.WaterWaveSettings updatedWaterWave =
                new RTXSettingsConfig.WaterWaveSettings(
                        waterWaveCalibration, waterWaveStrength, waterWaveBlend,
                        waterReflectCausticStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveParams(updatedWaterWave.toParamArray())) {
            RTXSettingsConfig.setWaterWaveSettings(updatedWaterWave);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterSssSettings updatedWaterSss =
                new RTXSettingsConfig.WaterSssSettings(
                        waterSssNonlinear, waterSssBoost, waterSssPhaseG, waterSssStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterSssParams(updatedWaterSss.toParamArray())) {
            RTXSettingsConfig.setWaterSssSettings(updatedWaterSss);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterSimWaveSettings updatedWaterSimWave =
                new RTXSettingsConfig.WaterSimWaveSettings(
                        waterSimSpongeWidth, waterSimGaussianSource,
                        waterSimDampingScale, waterSimPropagationSpeed);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterSimWaveParams(updatedWaterSimWave.toParamArray())) {
            RTXSettingsConfig.setWaterSimWaveSettings(updatedWaterSimWave);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterWaveShapeSettings updatedWaterWaveShape =
                new RTXSettingsConfig.WaterWaveShapeSettings(
                        waterWaveLengthScale, waterWaveDirection, waterWaveCrestSharpness,
                        waterWaveWarpStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveShapeParams(updatedWaterWaveShape.toParamArray())) {
            RTXSettingsConfig.setWaterWaveShapeSettings(updatedWaterWaveShape);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterWaveAflSettings updatedWaterAfl =
                new RTXSettingsConfig.WaterWaveAflSettings(
                        waterWaveAflScale, waterWaveAflLayers);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveAflParams(updatedWaterAfl.toParamArray())) {
            RTXSettingsConfig.setWaterWaveAflSettings(updatedWaterAfl);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterFftSettings updatedWaterFft =
                new RTXSettingsConfig.WaterFftSettings(
                        waterFftEnabled, waterFftAmplitude, waterFftChoppiness,
                        waterFftTilePeriod, waterFftDetail);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterFftParams(updatedWaterFft.toParamArray())) {
            RTXSettingsConfig.setWaterFftSettings(updatedWaterFft);
        } else {
            allApplied = false;
        }
        int effectiveTlasCull = postTlasCullDistance;
        if(effectiveTlasCull == 0 && this.minecraft != null) {
            effectiveTlasCull = Math.min(512, Math.max(64,
                    this.minecraft.options.renderDistance().get() * 16 * 5 / 4));
        }
        RTXSettingsConfig.PostProcessingSettings updatedPost =
                new RTXSettingsConfig.PostProcessingSettings(
                        postTonemapMode, postExposureStrength, postContrast,
                        postSaturation, postGamma, postMirrorBounceCount,
                        postTlasCullDistance, postTlasFrustumCullMargin,
                        postRrFarCloudDistance);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setPostProcessingParams(
                new float[] {
                    updatedPost.tonemapMode(), updatedPost.exposureStrength(),
                    updatedPost.contrast(), updatedPost.saturation(),
                    updatedPost.gamma(), updatedPost.mirrorBounceCount(),
                    effectiveTlasCull, postTlasFrustumCullMargin,
                    this.postBloomStrength,
                    this.postGainR, this.postGainG, this.postGainB })) {
            RTXSettingsConfig.setPostProcessingSettings(updatedPost);
            RTXSettingsConfig.setPostBloomStrength(this.postBloomStrength);
            RTXSettingsConfig.setPostGains(this.postGainR, this.postGainG, this.postGainB);
        } else {
            allApplied = false;
        }
        if(NativeVulkan.isLoaded() &&
                !NativeVulkan.setRrGuideSettings(true, postRrFarCloudDistance)) {
            this.postRrFarCloudDistance = (int)NativeVulkan.getRrFarCloudDistance();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudResFloorPercent(
                this.pendingCloudResFloorPercent)) {
            RTXSettingsConfig.setCloudResFloorPercent(
                    this.pendingCloudResFloorPercent);
        } else {
            this.pendingCloudResFloorPercent =
                    NativeVulkan.getCloudResFloorPercent();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudFarStepDensity(
                this.pendingCloudFarStepDensity)) {
            RTXSettingsConfig.setCloudFarStepDensity(
                    this.pendingCloudFarStepDensity);
        } else {
            this.pendingCloudFarStepDensity =
                    NativeVulkan.getCloudFarStepDensity();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setGiRoulette(
                this.pendingGiRoulette)) {
            RTXSettingsConfig.setGiRoulette(
                    this.pendingGiRoulette);
        } else {
            this.pendingGiRoulette =
                    NativeVulkan.getGiRoulette();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setDirectLightMode(
                this.pendingDirectLightMode)) {
            RTXSettingsConfig.setDirectLightMode(
                    this.pendingDirectLightMode);
        } else {
            this.pendingDirectLightMode =
                    NativeVulkan.getDirectLightMode();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setIndirectMode(
                this.pendingIndirectMode)) {
            RTXSettingsConfig.setIndirectMode(
                    this.pendingIndirectMode);
        } else {
            this.pendingIndirectMode =
                    NativeVulkan.getIndirectMode();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSparseRatePercent(
                this.pendingSparseRatePercent)) {
            RTXSettingsConfig.setSparseRatePercent(
                    this.pendingSparseRatePercent);
        } else {
            this.pendingSparseRatePercent =
                    NativeVulkan.getSparseRatePercent();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setMotionDebugView(
                this.pendingMotionDebugView)) {
            RTXSettingsConfig.setMotionDebugView(
                    this.pendingMotionDebugView);
        } else {
            this.pendingMotionDebugView =
                    NativeVulkan.getMotionDebugView();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSunMarchFarDistance(
                this.pendingSunMarchFarDistance)) {
            RTXSettingsConfig.setSunMarchFarDistance(
                    this.pendingSunMarchFarDistance);
        } else {
            this.pendingSunMarchFarDistance =
                    NativeVulkan.getSunMarchFarDistance();
            allApplied = false;
        }

        RTXSettingsConfig.CloudHpSettings updatedCloudHp =
                new RTXSettingsConfig.CloudHpSettings(
                        RTXSettingsConfig.getCloudHpSettings().toParamArray());
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudHpParams(updatedCloudHp.toParamArray())) {
            RTXSettingsConfig.setCloudHpSettings(updatedCloudHp);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.CloudSettings updatedClouds =
                new RTXSettingsConfig.CloudSettings(
                        this.pendingCloudEnabled,
                        this.pendingCloudBottom,
                        this.pendingCloudThickness,
                        this.pendingCloudCoverage,
                        this.pendingCloudDensity,
                        this.pendingCloudDetailStrength,
                        this.pendingCloudWindSpeed,
                        this.pendingCloudEvolution,
                        this.pendingCloudQuality,
                        this.pendingCloudSize,
                        this.pendingCloudDistribution,
                        this.pendingCloudBaseFeather,
                        this.pendingCloudSunlightStrength
                );

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudFadeFollowsRrMode(
                this.pendingCloudFadeFollowsRrMode)) {
            RTXSettingsConfig.setCloudFadeFollowsRrMode(
                    this.pendingCloudFadeFollowsRrMode);
        } else {
            this.pendingCloudFadeFollowsRrMode =
                    RTXSettingsConfig.isCloudFadeFollowsRrMode();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPostRrComposite(
                this.pendingCloudPostRrComposite)) {
            RTXSettingsConfig.setCloudPostRrComposite(
                    this.pendingCloudPostRrComposite);
        } else {
            this.pendingCloudPostRrComposite =
                    RTXSettingsConfig.isCloudPostRrComposite();
            allApplied = false;
        }
        RTXSettingsConfig.CloudMsSettings updatedCloudMs =
                new RTXSettingsConfig.CloudMsSettings(
                        msPhiIntensity, msPhiDepthPow, msPhiDepthBias, msOctaveStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMsParams(updatedCloudMs.toParamArray())) {
            RTXSettingsConfig.setCloudMsSettings(updatedCloudMs);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.PlaneCloudSettings updatedPlaneCloud =
                new RTXSettingsConfig.PlaneCloudSettings(
                        this.pendingPlaneCloudEnabled,
                        this.pendingPlaneCloudHeightOffset,
                        this.pendingPlaneCloudScale,
                        this.pendingPlaneCloudSpeed,
                        this.pendingPlaneCloudDensity,
                        this.pendingPlaneCloudCoverage,
                        Math.round(this.pendingPlaneCloudOctaves),
                        this.pendingPlaneCloudFadeDistance,
                        this.pendingPlaneCloudBrightness);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setPlaneCloudParams(updatedPlaneCloud.toParamArray())) {
            RTXSettingsConfig.setPlaneCloudSettings(updatedPlaneCloud);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudPhiSettings updatedCloudPhi =
                new RTXSettingsConfig.CloudPhiSettings(
                        phiBoundaryConfidence, phiMsBuildScale, phiCompress, phiConeRatio);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPhiParams(updatedCloudPhi.toParamArray())) {
            RTXSettingsConfig.setCloudPhiSettings(updatedCloudPhi);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudLightSettings updatedCloudLight =
                new RTXSettingsConfig.CloudLightSettings(
                        lightSourceOdScale, lightSourceCurvePow, lightAoUpwardScale,
                        lightSunsetAmbientTint);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudLightParams(updatedCloudLight.toParamArray())) {
            RTXSettingsConfig.setCloudLightSettings(updatedCloudLight);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudShapeSettings updatedCloudShape =
                new RTXSettingsConfig.CloudShapeSettings(
                        shapeBottomSmoothHeight, shapeBottomSmoothPow, shapeTwoTierThreshold);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudShapeParams(updatedCloudShape.toParamArray())) {
            RTXSettingsConfig.setCloudShapeSettings(updatedCloudShape);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudMoonSettings updatedCloudMoon =
                new RTXSettingsConfig.CloudMoonSettings(moonStrength, moonPhase);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMoonParams(updatedCloudMoon.toParamArray())) {
            RTXSettingsConfig.setCloudMoonSettings(updatedCloudMoon);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudShape2Settings updatedCloudShape2 =
                new RTXSettingsConfig.CloudShape2Settings(
                        shapeDetailScale, shapeDetailContrast, shapeProfileCurve);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudShape2Params(updatedCloudShape2.toParamArray())) {
            RTXSettingsConfig.setCloudShape2Settings(updatedCloudShape2);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudAmbientSettings updatedCloudAmbient =
                new RTXSettingsConfig.CloudAmbientSettings(
                        ambTopMul, ambBottomMul, ambSunSide, ambHueFollow);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudAmbientParams(updatedCloudAmbient.toParamArray())) {
            RTXSettingsConfig.setCloudAmbientSettings(updatedCloudAmbient);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudSkyHueSettings updatedCloudSkyHue =
                new RTXSettingsConfig.CloudSkyHueSettings(skyHueCool, skyHueEnergy,
                        skyDirStrength, ambientUnify);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSkyHueParams(updatedCloudSkyHue.toParamArray())) {
            RTXSettingsConfig.setCloudSkyHueSettings(updatedCloudSkyHue);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudPhysicsSettings updatedCloudPhysics =
                new RTXSettingsConfig.CloudPhysicsSettings(cloudGroundAlbedo,
                        cloudGroundBounce, cloudSunlightResidual, cloudPhaseFloor);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPhysicsParams(updatedCloudPhysics.toParamArray())) {
            RTXSettingsConfig.setCloudPhysicsSettings(updatedCloudPhysics);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudMs2Settings updatedCloudMs2 =
                new RTXSettingsConfig.CloudMs2Settings(
                        ms2ScatterTint, ms2Octaves, ms2Attenuation, ms2Eccentricity);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMs2Params(updatedCloudMs2.toParamArray())) {
            RTXSettingsConfig.setCloudMs2Settings(updatedCloudMs2);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudLight2Settings updatedCloudLight2 =
                new RTXSettingsConfig.CloudLight2Settings(
                        l2SunPathReddening, l2AtmosphereExtinction, l2PowderMode);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudLight2Params(updatedCloudLight2.toParamArray())) {
            RTXSettingsConfig.setCloudLight2Settings(updatedCloudLight2);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudProfileSettings updatedCloudProfile =
                new RTXSettingsConfig.CloudProfileSettings(profileType, profileTypeSpread);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudProfileParams(updatedCloudProfile.toParamArray())) {
            RTXSettingsConfig.setCloudProfileSettings(updatedCloudProfile);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudDetailSettings updatedCloudDetail =
                new RTXSettingsConfig.CloudDetailSettings(
                        wispyStrength, wispyEdgeWidth, wispyReach, wispyTopHeight);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudDetailParams(updatedCloudDetail.toParamArray())) {
            RTXSettingsConfig.setCloudDetailSettings(updatedCloudDetail);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudTemporalSettings updatedCloudTemporal =
                new RTXSettingsConfig.CloudTemporalSettings(
                        cloudSkyVisibilityStrength, cloudSkyVisibilitySteps, cloudTemporalDampStrength,
                        cloudBottomOcclusion);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudTemporalParams(updatedCloudTemporal.toParamArray())) {
            RTXSettingsConfig.setCloudTemporalSettings(updatedCloudTemporal);
        } else {
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSettings(
                updatedClouds.enabled(),
                updatedClouds.bottom(),
                updatedClouds.thickness(),
                updatedClouds.coverage(),
                updatedClouds.density(),
                updatedClouds.detailStrength(),
                updatedClouds.windSpeed(),
                updatedClouds.evolution(),
                updatedClouds.quality(),
                updatedClouds.size(),
                updatedClouds.distribution(),
                updatedClouds.baseFeather(),
                updatedClouds.sunlightStrength()
        )) {
            RTXSettingsConfig.setCloudSettings(updatedClouds);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.VolumetricFogSettings updatedFog =
                new RTXSettingsConfig.VolumetricFogSettings(
                        this.pendingFogEnabled,
                        this.pendingFogDensity,
                        this.pendingFogHeight,
                        this.pendingFogScaleHeight,
                        this.pendingFogAnisotropy,
                        this.pendingFogSunStrength,
                        this.pendingFogAmbientStrength,
                        this.pendingFogMaxDistance,
                        this.pendingFogStartDistance,
                        this.pendingFogSunSteps,
                        this.pendingFogRainBoost
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setVolumetricFogSettings(
                updatedFog.enabled(),
                updatedFog.density(),
                updatedFog.height(),
                updatedFog.scaleHeight(),
                updatedFog.anisotropy(),
                updatedFog.sunStrength(),
                updatedFog.ambientStrength(),
                updatedFog.maxDistance(),
                updatedFog.startDistance(),
                updatedFog.sunSteps(),
                updatedFog.rainBoost()
        )) {
            RTXSettingsConfig.setVolumetricFogSettings(updatedFog);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.FogWispSettings updatedFogWisp =
                new RTXSettingsConfig.FogWispSettings(
                        this.pendingFogWispEnabled,
                        this.pendingFogWispStrength,
                        this.pendingFogWispWavelength,
                        this.pendingFogWispSpeed
                );
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFogWispParams(updatedFogWisp.toParamArray())) {
            RTXSettingsConfig.setFogWispSettings(updatedFogWisp);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.CloudAerialSettings updatedCloudAerial =
                new RTXSettingsConfig.CloudAerialSettings(
                        cloudAerialStrength,
                        groundAerialStrength,
                        groundAerialOcclusion);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudAerialParams(updatedCloudAerial.toParamArray())) {
            RTXSettingsConfig.setCloudAerialSettings(updatedCloudAerial);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.VolumetricLightSettings updatedGodRays =
                new RTXSettingsConfig.VolumetricLightSettings(
                        this.pendingGodRayEnabled,
                        this.pendingGodRaySteps,
                        this.pendingGodRayOcclusionSamples,
                        this.pendingGodRayIntensity,
                        this.pendingGodRayReprojection
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setVolumetricLightSettings(
                updatedGodRays.enabled(),
                updatedGodRays.steps(),
                updatedGodRays.occlusionSamples(),
                updatedGodRays.intensity()
        )) {
            RTXSettingsConfig.setVolumetricLightSettings(updatedGodRays);
            if(NativeVulkan.isLoaded() &&
                    !NativeVulkan.setGodRayReprojection(updatedGodRays.reprojection())) {
                allApplied = false;
            }
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.SunVisualSettings updatedSunVisuals =
                new RTXSettingsConfig.SunVisualSettings(
                        this.pendingSunDiskLuminance,
                        this.pendingSunHaloStrength,
                        this.pendingSunAngularDiameterDegrees
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSunVisualSettings(
                updatedSunVisuals.diskLuminance(),
                updatedSunVisuals.haloStrength(),
                updatedSunVisuals.angularDiameterDegrees()
        )) {
            RTXSettingsConfig.setSunVisualSettings(updatedSunVisuals);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.AtmosphereSettings updatedAtmosphere =
                new RTXSettingsConfig.AtmosphereSettings(
                        this.pendingStarIntensity,
                        this.pendingSunTintStrength,
                        this.pendingPhysicalSkyEnabled,
                        this.pendingScatterIntensity,
                        this.pendingOzoneStrength
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setAtmosphereSettings(
                updatedAtmosphere.starIntensity(),
                updatedAtmosphere.sunTintStrength(),
                updatedAtmosphere.physicalSkyEnabled(),
                updatedAtmosphere.scatterIntensity(),
                updatedAtmosphere.ozoneStrength()
        )) {
            RTXSettingsConfig.setAtmosphereSettings(updatedAtmosphere);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setPowderStrength(
                this.pendingPowderStrength
        )) {
            RTXSettingsConfig.setPowderStrength(
                    this.pendingPowderStrength
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setAuroraBrightness(
                this.pendingAuroraBrightness
        )) {
            RTXSettingsConfig.setAuroraBrightness(
                    this.pendingAuroraBrightness
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setAuroraFlowSpeed(
                this.pendingAuroraFlowSpeed
        )) {
            RTXSettingsConfig.setAuroraFlowSpeed(
                    this.pendingAuroraFlowSpeed
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSunForwardScattering(
                this.pendingCloudSunForwardScattering
        )) {
            RTXSettingsConfig.setCloudSunForwardScattering(
                    this.pendingCloudSunForwardScattering
            );
        } else {
            allApplied = false;
        }


        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSunReddening(
                this.pendingCloudSunReddening
        )) {
            RTXSettingsConfig.setCloudSunReddening(
                    this.pendingCloudSunReddening
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudTopStretch(
                this.pendingCloudTopStretch
        )) {
            RTXSettingsConfig.setCloudTopStretch(
                    this.pendingCloudTopStretch
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudDetileStrength(
                this.pendingCloudDetileStrength
        )) {
            RTXSettingsConfig.setCloudDetileStrength(
                    this.pendingCloudDetileStrength
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudType(this.pendingCloudType)) {
            RTXSettingsConfig.setCloudType(this.pendingCloudType);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudErosionScale(
                this.pendingCloudErosionScale
        )) {
            RTXSettingsConfig.setCloudErosionScale(this.pendingCloudErosionScale);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudWeatherScale(
                this.pendingCloudWeatherScale
        )) {
            RTXSettingsConfig.setCloudWeatherScale(this.pendingCloudWeatherScale);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSilver(this.pendingCloudSilver)) {
            RTXSettingsConfig.setCloudSilver(this.pendingCloudSilver);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudViewDistance(
                this.pendingCloudViewDistance
        )) {
            RTXSettingsConfig.setCloudViewDistance(this.pendingCloudViewDistance);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.CloudMarchSettings updatedCloudMarch =
                new RTXSettingsConfig.CloudMarchSettings(
                        msAdaptiveLightRange, msTwoTierStep, msMeanReproject,
                        msFarFadeDistance);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMarchParams(updatedCloudMarch.toParamArray())) {
            RTXSettingsConfig.setCloudMarchSettings(updatedCloudMarch);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.WaterParamsSettings updatedWaterParams =
                new RTXSettingsConfig.WaterParamsSettings(
                        wpCrestBacklit, wpCausticDispersion, wpCausticDepthDiffusion,
                        wpPhysicalPhase);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterParams(updatedWaterParams.toParamArray())) {
            RTXSettingsConfig.setWaterParamsSettings(updatedWaterParams);
        } else {
            allApplied = false;
        }



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


        if(
                NativeVulkan.setPOMSharp(
                        this.pendingPOMSharp
                )
        )
        {
            RTXSettingsConfig.setPOMSharp(
                    this.pendingPOMSharp
            );
        }
        else
        {
            this.pendingPOMSharp =
                    NativeVulkan.getPOMSharp();

            allApplied =
                    false;
        }


        if(
                !NativeVulkan.setGiNearRate(
                        this.pendingGiNearRate
                )
        )
        {
            this.pendingGiNearRate =
                    NativeVulkan.getGiNearRate();

            allApplied =
                    false;
        }

        if(
                !NativeVulkan.setGlossyRoughLod(
                        this.pendingGlossyRoughLod
                )
        )
        {
            this.pendingGlossyRoughLod =
                    NativeVulkan.getGlossyRoughLod();

            allApplied =
                    false;
        }


        for(int i = 0; i < RTXSettingsConfig.UE_CLOUD_PARAM_COUNT; i++) {

            if(
                    !NativeVulkan.isLoaded()
                            || NativeVulkan.setUeCloudParam(
                                    i,
                                    this.pendingUeCloud[i]
                            )
            )
            {
                RTXSettingsConfig.setUeCloud(
                        i,
                        this.pendingUeCloud[i]
                );
            }
            else
            {
                this.pendingUeCloud[i] =
                        NativeVulkan.getUeCloudParam(i);

                allApplied =
                        false;
            }
        }


        if(
                NativeVulkan.setSSSMode(
                        this.pendingTrueSubsurfaceScattering
                )
        )
        {
            RTXSettingsConfig.setTrueSubsurfaceScattering(
                    this.pendingTrueSubsurfaceScattering
            );
        }
        else
        {
            this.pendingTrueSubsurfaceScattering =
                    NativeVulkan.getSSSMode();

            allApplied =
                    false;
        }


        if(
                NativeVulkan.setLocalLightMaxDistance(
                        this.pendingLocalLightMaxDistance
                )
        )
        {
            RTXSettingsConfig.setLocalLightMaxDistance(
                    this.pendingLocalLightMaxDistance
            );
        }
        else
        {
            this.pendingLocalLightMaxDistance =
                    NativeVulkan.getLocalLightMaxDistance();

            allApplied =
                    false;
        }


        if(
                NativeVulkan.setLocalLightIntensity(
                        this.pendingLocalLightIntensity
                )
        )
        {
            RTXSettingsConfig.setLocalLightIntensity(
                    this.pendingLocalLightIntensity
            );
        }
        else
        {
            this.pendingLocalLightIntensity =
                    NativeVulkan.getLocalLightIntensity();

            allApplied =
                    false;
        }


        try
        {
            RTXSettingsConfig.setDynamicLightIntensity(
                    this.pendingDynamicLightIntensity
            );
        }
        catch(IllegalArgumentException invalidDynamicLightIntensity)
        {
            this.pendingDynamicLightIntensity =
                    RTXSettingsConfig.getDynamicLightIntensity();

            allApplied =
                    false;
        }


        final boolean hdrChanged =
                RTXSettingsConfig.isHDREnabled() !=
                        this.pendingHDR;

        if(NativeVulkan.isLoaded())
        {
            NativeVulkan.setHDREnabledNative(
                    this.pendingHDR
            );

            if(hdrChanged)
            {
                NativeVulkan.hdrSwapchainDirty =
                        true;
            }
        }

        RTXSettingsConfig.setHDREnabled(
                this.pendingHDR
        );


        if(NativeVulkan.isLoaded())
        {
            NativeVulkan.setAutoExposureEnabledNative(
                    this.pendingAutoExposureEnabled
            );

            if(!NativeVulkan.setAutoExposureCompensationNative(
                    this.pendingAutoExposureCompensation
            ))
            {
                this.pendingAutoExposureCompensation =
                        NativeVulkan.getAutoExposureCompensation();

                allApplied =
                        false;
            }
        }

        RTXSettingsConfig.setAutoExposureEnabled(
                this.pendingAutoExposureEnabled
        );

        RTXSettingsConfig.setAutoExposureCompensation(
                this.pendingAutoExposureCompensation
        );


        System.out.println(
                allApplied
                        ? "[RTX SETTINGS] Confirmed"
                        : "[RTX SETTINGS] Confirmed with native failure"
        );

        RTXSettingsConfig.flush();
    }

    private static Component qualityLabel(int mode) {
        return switch(mode) {
            case RTXSettingsConfig.RR_MODE_PERFORMANCE -> Component.literal("Performance");
            case RTXSettingsConfig.RR_MODE_BALANCED -> Component.literal("Balanced");
            case RTXSettingsConfig.RR_MODE_QUALITY -> Component.literal("Quality");
            case RTXSettingsConfig.RR_MODE_DLAA -> Component.literal("DLAA");
            case RTXSettingsConfig.RR_MODE_ULTRA_PERFORMANCE -> Component.literal("Ultra Performance");
            default -> Component.literal("Quality");
        };
    }

    private CycleButton<Integer> performanceButton(
            String label,
            int current,
            List<Integer> values,
            IntConsumer setter
    ) {
        return CycleButton.builder(
                        value -> Component.literal(
                                value == 5
                                        ? "关闭"
                                        : value == 0 ? "Off" : Integer.toString(value)
                        ),
                        current
                )
                .withValues(values)
                .create(
                        Component.literal(label),
                        (button, value) -> setter.accept(value)
                );
    }

    private void loadCloudSettings(RTXSettingsConfig.CloudSettings clouds) {
        this.pendingCloudEnabled = clouds.enabled();
        this.pendingCloudBottom = clouds.bottom();
        this.pendingCloudThickness = clouds.thickness();
        this.pendingCloudCoverage = clouds.coverage();
        this.pendingCloudDensity = clouds.density();
        this.pendingCloudDetailStrength = clouds.detailStrength();
        this.pendingCloudWindSpeed = clouds.windSpeed();
        this.pendingCloudEvolution = clouds.evolution();
        this.pendingCloudQuality = clouds.quality();
        this.pendingCloudSize = clouds.size();
        this.pendingCloudDistribution = clouds.distribution();
        this.pendingCloudBaseFeather = clouds.baseFeather();
        this.pendingCloudSunlightStrength = clouds.sunlightStrength();
    }

    private void loadCloudMsSettings(RTXSettingsConfig.CloudMsSettings ms) {
        this.msPhiIntensity = ms.phiIntensity();
        this.msPhiDepthPow = ms.phiDepthPow();
        this.msPhiDepthBias = ms.phiDepthBias();
        this.msOctaveStrength = ms.octaveStrength();
    }

    private void loadCloudPhiSettings(RTXSettingsConfig.CloudPhiSettings phi) {
        this.phiBoundaryConfidence = phi.boundaryConfidence();
        this.phiMsBuildScale = phi.msBuildScale();
        this.phiCompress = phi.compress();
        this.phiConeRatio = phi.coneRatio();
    }

    private void loadCloudLightSettings(RTXSettingsConfig.CloudLightSettings light) {
        this.lightSourceOdScale = light.sourceOdScale();
        this.lightSourceCurvePow = light.sourceCurvePow();
        this.lightAoUpwardScale = light.aoUpwardScale();
        this.lightSunsetAmbientTint = light.sunsetAmbientTint();
    }

    private void loadCloudShapeSettings(RTXSettingsConfig.CloudShapeSettings shape) {
        this.shapeBottomSmoothHeight = shape.bottomSmoothHeight();
        this.shapeBottomSmoothPow = shape.bottomSmoothPow();
        this.shapeTwoTierThreshold = shape.twoTierThreshold();
    }

    private void loadCloudMoonSettings(RTXSettingsConfig.CloudMoonSettings moon) {
        this.moonStrength = moon.moonStrength();
        this.moonPhase = moon.moonPhase();
    }

    private void loadCloudShape2Settings(RTXSettingsConfig.CloudShape2Settings shape2) {
        this.shapeDetailScale = shape2.detailScale();
        this.shapeDetailContrast = shape2.detailContrast();
        this.shapeProfileCurve = shape2.profileCurve();
    }

    private void loadCloudAmbientSettings(RTXSettingsConfig.CloudAmbientSettings ambient) {
        this.ambTopMul = ambient.topMultiplier();
        this.ambBottomMul = ambient.bottomMultiplier();
        this.ambSunSide = ambient.sunSideStrength();
        this.ambHueFollow = ambient.hueFollow();
    }

    private void loadCloudSkyHueSettings(RTXSettingsConfig.CloudSkyHueSettings skyHue) {
        this.skyHueCool = skyHue.skyHueCool();
        this.skyHueEnergy = skyHue.skyHueEnergy();
        this.skyDirStrength = skyHue.skyDirStrength();
        this.ambientUnify = skyHue.ambientUnify();
    }

    private void loadCloudPhysicsSettings(RTXSettingsConfig.CloudPhysicsSettings physics) {
        this.cloudGroundAlbedo = physics.groundAlbedo();
        this.cloudGroundBounce = physics.groundBounce();
        this.cloudSunlightResidual = physics.sunlightResidual();
        this.cloudPhaseFloor = physics.phaseFloor();
    }

    private void loadCloudMs2Settings(RTXSettingsConfig.CloudMs2Settings ms2) {
        this.ms2ScatterTint = ms2.scatterTint();
        this.ms2Octaves = ms2.octaves();
        this.ms2Attenuation = ms2.attenuation();
        this.ms2Eccentricity = ms2.eccentricity();
    }

    private void loadCloudLight2Settings(RTXSettingsConfig.CloudLight2Settings light2) {
        this.l2SunPathReddening = light2.sunPathReddening();
        this.l2AtmosphereExtinction = light2.atmosphereExtinction();
        this.l2PowderMode = light2.powderMode();
    }

    private void loadCloudProfileSettings(RTXSettingsConfig.CloudProfileSettings profile) {
        this.profileType = profile.cloudType();
        this.profileTypeSpread = profile.typeSpread();
    }

    private void loadCloudDetailSettings(RTXSettingsConfig.CloudDetailSettings detail) {
        this.wispyStrength = detail.wispyStrength();
        this.wispyEdgeWidth = detail.wispyEdgeWidth();
        this.wispyReach = detail.wispyReach();
        this.wispyTopHeight = detail.wispyTopHeight();
    }

    private void loadCloudTemporalSettings(RTXSettingsConfig.CloudTemporalSettings temporal) {
        this.cloudSkyVisibilityStrength = temporal.skyVisibilityStrength();
        this.cloudSkyVisibilitySteps = temporal.skyVisibilitySteps();
        this.cloudTemporalDampStrength = temporal.temporalDampStrength();
        this.cloudBottomOcclusion = temporal.bottomOcclusion();
    }

    private void loadFogExtrasSettings(RTXSettingsConfig.FogExtrasSettings extras) {
        this.fogExtrasDynamicLightStrength = extras.dynamicLightStrength();
        this.fogExtrasGroundAnchorStrength = extras.groundAnchorStrength();
        this.fogExtrasSkyAmbientStrength = extras.skyAmbientStrength();
        this.fogExtrasTemporalJitterStrength = extras.temporalJitterStrength();
    }

    private void loadCloudAerialSettings(RTXSettingsConfig.CloudAerialSettings aerial) {
        this.cloudAerialStrength = aerial.cloudAerialStrength();
        this.groundAerialStrength = aerial.groundAerialStrength();
        this.groundAerialOcclusion = aerial.groundAerialOcclusion();
    }

    private void loadCloudMarchSettings(RTXSettingsConfig.CloudMarchSettings m) {
        this.msAdaptiveLightRange = m.adaptiveLightRange();
        this.msFarFadeDistance = m.farFadeDistance();
        this.msTwoTierStep = m.twoTierStep();
        this.msMeanReproject = m.meanDepthReproject();
    }

    private void loadWaterParamsSettings(RTXSettingsConfig.WaterParamsSettings wp) {
        this.wpCrestBacklit = wp.crestBacklit();
        this.wpCausticDispersion = wp.causticDispersion();
        this.wpCausticDepthDiffusion = wp.causticDepthDiffusion();
        this.wpPhysicalPhase = wp.physicalPhase();
    }

    private void loadWaterWaveSettings(RTXSettingsConfig.WaterWaveSettings wave) {
        this.waterWaveCalibration = wave.calibration();
        this.waterWaveStrength = wave.strength();
        this.waterWaveBlend = wave.blend();
        this.waterReflectCausticStrength = wave.reflectCausticStrength();
    }

    private void loadWaterSimWaveSettings(RTXSettingsConfig.WaterSimWaveSettings sim) {
        this.waterSimSpongeWidth = sim.spongeWidth();
        this.waterSimGaussianSource = sim.gaussianSource();
        this.waterSimDampingScale = sim.dampingScale();
        this.waterSimPropagationSpeed = sim.propagationSpeed();
    }

    private void loadWaterFftSettings(RTXSettingsConfig.WaterFftSettings fft) {
        this.waterFftEnabled = fft.enabled();
        this.waterFftAmplitude = fft.amplitude();
        this.waterFftChoppiness = fft.choppiness();
        this.waterFftTilePeriod = fft.tilePeriod();
        this.waterFftDetail = fft.detail();
    }

    private static final int PRESET_NEUTRAL = 0;
    private static final int PRESET_ICELAND = 1;
    private static final int PRESET_GREENLAND = 2;
    private static final int PRESET_MIST = 3;

    private static final float[] GRADE_ICELAND = {
            0.10f, 0.015f, 0.94f, 1.05f, 0.17f,
            0.008f, 0.014f, 0.024f,
            0.98f, 1.00f, 1.05f,
            0.94f, 1.00f, 1.07f,
            195.0f, 0.32f, 215.0f, 0.15f,
            -0.08f, 0.16f, 0.20f, 0.62f, -6.0f, 0.05f
    };

    private static final float[] GRADE_GREENLAND = {
            -0.25f, 0.02f, 0.88f, 1.12f, 0.14f,
            0.005f, 0.010f, 0.026f,
            0.96f, 1.00f, 1.08f,
            0.90f, 1.00f, 1.12f,
            205.0f, 0.45f, 220.0f, 0.10f,
            -0.15f, 0.10f, 0.34f, 0.50f, -10.0f, 0.02f
    };

    private static final float[] GRADE_MIST = {
            0.20f, 0.05f, 0.85f, 0.90f, 0.20f,
            0.030f, 0.032f, 0.036f,
            1.00f, 1.00f, 1.00f,
            0.98f, 1.00f, 1.02f,
            180.0f, 0.15f, 210.0f, 0.10f,
            -0.25f, 0.30f, 0.12f, 0.70f, 0.0f, 0.0f
    };

    private void applyColorGradePreset(int preset) {
        float[] grade = RTXSettingsConfig.DEFAULT_COLOR_GRADE.clone();

        if(preset == PRESET_ICELAND) {
            grade = GRADE_ICELAND.clone();
        } else if(preset == PRESET_GREENLAND) {
            grade = GRADE_GREENLAND.clone();
        } else if(preset == PRESET_MIST) {
            grade = GRADE_MIST.clone();
        }

        RTXSettingsConfig.ColorGradeSettings updated =
                new RTXSettingsConfig.ColorGradeSettings(grade);
        if(!NativeVulkan.isLoaded()
                || NativeVulkan.setColorGradeParams(updated.toParamArray())) {
            RTXSettingsConfig.setColorGradeSettings(updated);
        }

        if(this.minecraft != null) {
            this.minecraft.execute(this::rebuildPage);
        } else {
            this.rebuildPage();
        }
    }

    private static Component postTonemapLabel(int mode) {

        switch (mode) {
            case 1: return Component.literal("AgX");
            case 2: return Component.literal("PBR 中性");
            case 3: return Component.literal("ACES");
            case 4: return Component.literal("Uncharted 2");
            case 5: return Component.literal("Reinhard");
            case 6: return Component.literal("ACES 2.0");
            default: return Component.literal("Uchimura / GT");
        }
    }

    private void loadPostProcessingSettings(RTXSettingsConfig.PostProcessingSettings post) {
        this.postTonemapMode = post.tonemapMode();
        this.postExposureStrength = post.exposureStrength();
        this.postContrast = post.contrast();
        this.postSaturation = post.saturation();
        this.postGamma = post.gamma();
        this.postMirrorBounceCount = post.mirrorBounceCount();
        this.postTlasCullDistance = post.tlasCullDistance();
        this.postTlasFrustumCullMargin = post.tlasFrustumCullMargin();
        this.postRrFarCloudDistance = post.rrFarCloudDistance();
        this.postBloomStrength = RTXSettingsConfig.getPostBloomStrength();
        this.postGainR = RTXSettingsConfig.getPostGainR();
        this.postGainG = RTXSettingsConfig.getPostGainG();
        this.postGainB = RTXSettingsConfig.getPostGainB();
    }

    private void loadWaterWaveShapeSettings(RTXSettingsConfig.WaterWaveShapeSettings shape) {
        this.waterWaveLengthScale = shape.lengthScale();
        this.waterWaveDirection = shape.directionDegrees();
        this.waterWaveCrestSharpness = shape.crestSharpness();
        this.waterWaveWarpStrength = shape.warpStrength();
    }

    private void loadWaterWaveAflSettings(RTXSettingsConfig.WaterWaveAflSettings afl) {
        this.waterWaveAflScale = afl.wavelength();
        this.waterWaveAflLayers = afl.octaves();
    }

    private void loadWaterSssSettings(RTXSettingsConfig.WaterSssSettings sss) {
        this.waterSssNonlinear = sss.nonlinear();
        this.waterSssBoost = sss.boost();
        this.waterSssPhaseG = sss.phaseG();
        this.waterSssStrength = sss.strength();
    }

    private void addOptionRow(AbstractWidget widget) {
        if(this.section != this.page) {
            return;
        }
        widget.setWidth(150);
        this.optionRow.add(widget);
        if(this.optionRow.size() == 2) {
            this.list.addSmall(this.optionRow.get(0), this.optionRow.get(1));
            this.optionRow.clear();
        }
    }

    private void flushOptionRow() {
        if(!this.optionRow.isEmpty()) {
            this.list.addBig(this.optionRow.get(0));
            this.optionRow.clear();
        }
    }

    private void addSectionHeader(String title) {
        if(this.section != this.page) {
            return;
        }
        this.flushOptionRow();
        Button header = Button.builder(Component.literal(title), button -> {})
                .bounds(0, 0, 310, 20)
                .build();
        header.active = false;
        this.list.addBig(header);
    }

    private AbstractSliderButton cloudSlider(
            String label,
            double minimum,
            double maximum,
            double increment,
            float current,
            String format,
            DoubleConsumer setter
    ) {
        return new AbstractSliderButton(
                0,
                0,
                310,
                20,
                Component.empty(),
                (current - minimum) / (maximum - minimum)
        ) {
            {
                this.updateMessage();
            }

            private double snapped() {
                return Math.round((minimum + this.value * (maximum - minimum)) / increment) * increment;
            }

            @Override
            protected void updateMessage() {
                final double shown = this.snapped();
                this.setMessage(Component.literal(label + ": " + (
                        shown < 0.0
                                ? "内置默认"
                                : String.format(
                                        java.util.Locale.ROOT, format, shown))));
            }

            @Override
            protected void applyValue() {
                double value = Math.clamp(this.snapped(), minimum, maximum);
                this.value = (value - minimum) / (maximum - minimum);
                setter.accept(value);
                this.updateMessage();
            }
        };
    }

    @Override
    protected void addFooter() {

        LinearLayout footer =
                LinearLayout.horizontal()
                        .spacing(
                                8
                        );


        if(this.page == PAGE_HUB) {
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
        } else {
            footer.addChild(
                    Button.builder(
                                    Component.literal(
                                            "应用"
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
                                            this.openPage(this.parentPage())
                            )
                            .width(
                                    150
                            )
                            .build()
            );
        }


        this.layout.addToFooter(
                footer
        );
    }


    private int parentPage() {
        return this.page == PAGE_COLORGRADE ? PAGE_POST : PAGE_HUB;
    }


    private void openPage(int target) {
        this.page = target;

        if(this.minecraft != null) {
            this.minecraft.execute(this::rebuildPage);
        } else {
            this.rebuildPage();
        }
    }


    private void rebuildPage() {
        this.layout.removeChildren();
        this.rebuildWidgets();
    }


    private void addHubButtons() {
        this.addOptionRow(this.hubButton("超分与帧生成", PAGE_UPSCALER));
        this.addOptionRow(this.hubButton("光追性能", PAGE_PERF));
        this.addOptionRow(this.hubButton("天空与大气", PAGE_SKY));
        this.addOptionRow(this.hubButton("体积云", PAGE_CLOUD));
        this.addOptionRow(this.hubButton("体积雾与体积光", PAGE_FOG));
        this.addOptionRow(this.hubButton("降雨", PAGE_RAIN));
        this.addOptionRow(this.hubButton("水面", PAGE_WATER));
        this.addOptionRow(this.hubButton("材质与画面", PAGE_MATERIAL));
        this.addOptionRow(this.hubButton("后处理", PAGE_POST));
        this.flushOptionRow();
    }


    private AbstractWidget hubButton(String label, int target) {
        return Button.builder(
                Component.literal(label),
                button -> this.openPage(target)
        ).width(150).build();
    }


}
