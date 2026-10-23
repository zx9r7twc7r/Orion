package com.example.client;

public final class RRFovState {

    private static volatile float calculatedFovDegrees =
            Float.NaN;

    private RRFovState() {
    }

    public static void setCalculatedFovDegrees(
            float fovDegrees
    ) {
        calculatedFovDegrees =
                fovDegrees;
    }

    public static void invalidateCalculatedFov() {
        calculatedFovDegrees =
                Float.NaN;
    }

    public static float getCalculatedFovDegrees() {
        return calculatedFovDegrees;
    }

    public static boolean hasValidCalculatedFov() {
        return Float.isFinite(calculatedFovDegrees)
                && calculatedFovDegrees > 0.0f;
    }
}