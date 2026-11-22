package com.example.dsp

/**
 * One-Tap RF & Dead-Reckoning Calibration Presets synchronized across the
 * Top Tactical HUD quick carrying pill and the collapsible Calibration Drawer.
 */
enum class ShieldPreset(
    val displayName: String,
    val shortLabel: String,
    val shieldDropDb: Float
) {
    OMNI_HANDHELD(
        displayName = "Omni / Handheld (No Shield)",
        shortLabel = "Omni 0dB",
        shieldDropDb = 0.0f
    ),
    POCKET_BODY(
        displayName = "Breast Pocket / Body Shield",
        shortLabel = "Body 16dB",
        shieldDropDb = 16.0f
    ),
    FLAT_ALUMINUM(
        displayName = "Flat Aluminum Plate",
        shortLabel = "Flat 22dB",
        shieldDropDb = 22.0f
    ),
    CORNER_REFLECTOR_90(
        displayName = "90° Aluminum Corner Reflector",
        shortLabel = "Corner 27dB",
        shieldDropDb = 27.0f
    );

    /** Cycles the Top HUD Quick Carrying Mode pill: Omni 0dB -> Body 16dB -> Corner 27dB. */
    fun nextQuickCycle(): ShieldPreset = when (this) {
        OMNI_HANDHELD -> POCKET_BODY
        POCKET_BODY -> CORNER_REFLECTOR_90
        FLAT_ALUMINUM -> CORNER_REFLECTOR_90
        CORNER_REFLECTOR_90 -> OMNI_HANDHELD
    }

    fun contextualShortLabel(screenOutwardMode: Boolean): String {
        return if (this == POCKET_BODY && screenOutwardMode) {
            "Pocket +180° 16dB"
        } else {
            shortLabel
        }
    }
}

enum class EmitterClassPreset(
    val displayName: String,
    val shortLabel: String,
    val txPowerP0Dbm: Float
) {
    BLE_TRACKER(
        displayName = "BLE Tracker / AirTag / Tag",
        shortLabel = "BLE Tag -58",
        txPowerP0Dbm = -58.0f
    ),
    SMARTPHONE_WEARABLE(
        displayName = "Smartphone / Laptop / Wearable",
        shortLabel = "Phone -48",
        txPowerP0Dbm = -48.0f
    ),
    WIFI_ROUTER(
        displayName = "Wi-Fi Router / Hotspot",
        shortLabel = "Router -40",
        txPowerP0Dbm = -40.0f
    )
}

enum class EnvironmentPreset(
    val displayName: String,
    val shortLabel: String,
    val pathLossExponentN: Float,
    val stepLengthMeters: Float
) {
    OUTDOORS_OPEN(
        displayName = "Outdoors / Open Park",
        shortLabel = "Outdoors 2.0",
        pathLossExponentN = 2.0f,
        stepLengthMeters = 0.75f
    ),
    INDOORS_DRYWALL(
        displayName = "Indoors / Drywall House",
        shortLabel = "House 2.6",
        pathLossExponentN = 2.6f,
        stepLengthMeters = 0.65f
    ),
    DENSE_CONCRETE(
        displayName = "Dense Concrete / Commercial",
        shortLabel = "Concrete 3.4",
        pathLossExponentN = 3.4f,
        stepLengthMeters = 0.62f
    )
}

data class ActivePresetState(
    val shieldPreset: ShieldPreset? = ShieldPreset.POCKET_BODY,
    val emitterClassPreset: EmitterClassPreset? = null,
    val environmentPreset: EnvironmentPreset? = null,
    /** Non-null when a BLE 0x0A TX Power advertisement automatically overrode P0. */
    val autoOverriddenTxPowerDbm: Int? = null,
    /** Manual +180 deg heading flip for placing the phone screen-outward in a breast pocket. */
    val screenOutwardMode: Boolean = false
)

object CalibrationPresetApplier {
    fun applyShield(params: CalibrationParams, preset: ShieldPreset): CalibrationParams {
        return params.copy(shieldDropDb = preset.shieldDropDb)
    }

    fun applyEmitterClass(params: CalibrationParams, preset: EmitterClassPreset): CalibrationParams {
        return params.copy(txPowerP0Dbm = preset.txPowerP0Dbm)
    }

    fun applyEnvironment(params: CalibrationParams, preset: EnvironmentPreset): CalibrationParams {
        return params.copy(
            pathLossExponentN = preset.pathLossExponentN,
            stepLengthMeters = preset.stepLengthMeters
        )
    }

    /**
     * Auto-overrides P0 whenever a BLE packet advertises an explicit 0x0A TX Power Level.
     * Converts 0m output TX power (> -20 dBm) to 1m reference via 41 dB 2.4 GHz free-space loss,
     * clamping strictly to [-70, -20] dBm.
     */
    fun applyBleTxPowerOverride(
        params: CalibrationParams,
        advertisedTxPower0x0ADbm: Int
    ): CalibrationParams {
        val calibratedAt1m = if (advertisedTxPower0x0ADbm > -20) {
            advertisedTxPower0x0ADbm - 41
        } else {
            advertisedTxPower0x0ADbm
        }
        val clamped = calibratedAt1m.toFloat()
            .coerceIn(CalibrationParams.MIN_P0_DBM, CalibrationParams.MAX_P0_DBM)
        return params.copy(txPowerP0Dbm = clamped)
    }
}
