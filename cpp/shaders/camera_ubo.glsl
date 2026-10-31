
    mat4 viewInverse;
    mat4 projInverse;
    uvec2 vertexBufferAddr;
    uint frameCount;
    uint padding;

    vec4 cameraParams;

    uint historyReadIndex;
    uint historyValid;
    uint historyPadding0;
    uint historyPadding1;

    uvec4 perfPrimary;
    uvec4 perfSecondary;

    mat4 prevViewInverse;
    mat4 prevProjInverse;

    vec4 historyParams;

    vec4 playerPositionAndWater;
    vec4 playerVelocityAndSpeed;

    vec4 waterSimSurfaceAndOrigin;
    uvec4 waterSimState;
    vec4 waterSimImpulse;

    vec4 weatherParams;

    vec4 cloudParams0;
    vec4 cloudParams1;
    vec4 cloudParams2;
    vec4 cloudParams3;
    vec4 cloudParams4;
    vec4 cloudParams5;
    vec4 cloudParams6;
    vec4 cloudParams7;
    vec4 cloudParams8;
    vec4 cloudParams9;
    vec4 skyParams0;
    vec4 skyParams1;

    vec4 godRayParams0;
    vec4 godRayParams1;
    vec4 godRayParams2;

    vec4 rainWallParams0;
    vec4 rainWallParams1;
    vec4 waterStyleParams0;
    vec4 waterStyleParams1;

    uvec4 perfSerAb;

    vec4 dhFarFieldParams;
    mat4 dhFarFieldInvMvp;
    vec4 dhFarFieldLight;
    mat4 dhFarFieldMvp;

    vec4 sunShadowFarParams;

    vec4 highCloudParams0;
    vec4 highCloudParams1;
    vec4 highCloudParams2;
    vec4 highCloudParams3;
    vec4 highCloudParams4;

    vec4 cloudMsParams;
    vec4 cloudMarchParams;
    vec4 waterParams;

    vec4 cloudPhiParams;
    vec4 cloudLightParams;
    vec4 cloudShapeParams;
    vec4 cloudMoonParams;
    vec4 cloudShape2Params;

    vec4 cloudAmbientParams;
    vec4 cloudMsParams2;
    vec4 cloudLightParams2;
    vec4 cloudProfileParams;
    vec4 cloudDetailParams;
    vec4 waterWaveParams;
    vec4 waterWaveShapeParams;
    vec4 waterSssParams;
    vec4 waterFftParams;
    vec4 waterFftParams2;

    vec4 auroraParams;
    vec4 rtSampleParams;
    uvec4 perfAblationStage;
    vec4 galaxyParams;

    vec4 fogExtrasParams;
    vec4 cloudTemporalParams;

    vec4 cloudAerialParams;

    uvec4 sharcBuffersLo;
    uvec4 sharcBuffersHi;

    uvec4 sharcCacheParams;

    vec4 cloudSkyHueParams;

    vec4 cloudPhysicsParams;

    vec4 pomParams;

    vec4 sssParams;

    vec4 ueCloudA;
    vec4 ueCloudB;

    vec4 giHalfParams;

    vec4 giRayParams;

    vec4 hpShapeA;
    vec4 hpShapeB;
    vec4 hpShapeC;
    vec4 hpShapeD;
    vec4 hpLightA;
    vec4 hpLightB;
    vec4 hpMiscA;

    vec4 sparseParams;
