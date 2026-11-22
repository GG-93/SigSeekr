package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.dsp.ActivePresetState
import com.example.dsp.CalibrationParams
import com.example.dsp.EmitterClassPreset
import com.example.dsp.EnvironmentPreset
import com.example.dsp.ShieldPreset
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ReadoutInkMuted
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TelemetrySubValueStyle
import com.example.ui.theme.ThermalAmber
import java.util.Locale

/**
 * Collapsible Bottom Drawer for One-Tap RF & Dead-Reckoning Presets plus Live Sliders:
 * - Carrying / Shield Drop A_max: 0–30 dB (Omni 0dB, Pocket 16dB, Flat 22dB, Corner 27dB)
 * - Emitter Class / Tx Power P0: -70 to -20 dBm (with 0x0A AUTO override indicator)
 * - Environment / Path Loss n (1.8–4.0) & Stride Length L_step (0.50–1.00 m)
 */
@Composable
fun CalibrationDrawer(
    calibration: CalibrationParams,
    activePresets: ActivePresetState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onApplyShieldPreset: (ShieldPreset) -> Unit,
    onApplyEmitterClassPreset: (EmitterClassPreset) -> Unit,
    onApplyEnvironmentPreset: (EnvironmentPreset) -> Unit,
    onShieldDropChange: (Float) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onPathLossChange: (Float) -> Unit,
    onStepLengthChange: (Float) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .navigationBarsPadding()
    ) {
        // Drawer Handle / Summary Bar (min 48.dp touch target)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("calibration_drawer_toggle"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.cd_calibration_drawer),
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "CALIBRATION & PRESETS",
                style = MaterialTheme.typography.titleSmall,
                color = ReadoutInkPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = String.format(
                    Locale.US,
                    "A %.0fdB · P₀ %.0f · n %.1f · L %.2fm",
                    calibration.shieldDropDb,
                    calibration.txPowerP0Dbm,
                    calibration.pathLossExponentN,
                    calibration.stepLengthMeters
                ),
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = ReadoutInkSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // One-Tap Carrying / Shield Presets (0..30 dB)
                PresetGroupHeader(
                    title = "CARRYING / SHIELD PRESET (A_max)",
                    badgeText = activePresets.shieldPreset?.shortLabel ?: "CUSTOM"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShieldPreset.entries.forEach { preset ->
                        PresetChip(
                            label = preset.shortLabel,
                            selected = activePresets.shieldPreset == preset,
                            accent = ElectricCyan,
                            testTag = "preset_shield_${preset.name.lowercase(Locale.US)}",
                            onClick = { onApplyShieldPreset(preset) }
                        )
                    }
                }

                // One-Tap Emitter Class Presets (P0)
                PresetGroupHeader(
                    title = "TARGET CLASS PRESET (P₀)",
                    badgeText = activePresets.autoOverriddenTxPowerDbm?.let {
                        "0x0A AUTO (${it}dBm)"
                    } ?: activePresets.emitterClassPreset?.shortLabel ?: "CUSTOM",
                    badgeColor = if (activePresets.autoOverriddenTxPowerDbm != null) SignalEmerald else ReadoutInkSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EmitterClassPreset.entries.forEach { preset ->
                        PresetChip(
                            label = preset.shortLabel,
                            selected = activePresets.emitterClassPreset == preset &&
                                activePresets.autoOverriddenTxPowerDbm == null,
                            accent = ThermalAmber,
                            testTag = "preset_emitter_${preset.name.lowercase(Locale.US)}",
                            onClick = { onApplyEmitterClassPreset(preset) }
                        )
                    }
                }

                // One-Tap Environment Presets (n & L_step)
                PresetGroupHeader(
                    title = "ENVIRONMENT PRESET (n & STRIDE)",
                    badgeText = activePresets.environmentPreset?.shortLabel ?: "CUSTOM"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EnvironmentPreset.entries.forEach { preset ->
                        PresetChip(
                            label = preset.shortLabel,
                            selected = activePresets.environmentPreset == preset,
                            accent = SignalEmerald,
                            testTag = "preset_env_${preset.name.lowercase(Locale.US)}",
                            onClick = { onApplyEnvironmentPreset(preset) }
                        )
                    }
                }

                HorizontalDivider(color = TacticalOutline, modifier = Modifier.padding(vertical = 2.dp))

                // Fine-Tuning Live Sliders
                CalibrationSliderRow(
                    label = "Shield Drop (A_max)",
                    formattedValue = String.format(Locale.US, "%.0f dB", calibration.shieldDropDb),
                    value = calibration.shieldDropDb,
                    valueRange = CalibrationParams.MIN_SHIELD_DB..CalibrationParams.MAX_SHIELD_DB,
                    onValueChange = onShieldDropChange,
                    testTag = "slider_shield_drop"
                )

                CalibrationSliderRow(
                    label = "Tx Power @ 1m (P₀)",
                    formattedValue = String.format(Locale.US, "%.0f dBm", calibration.txPowerP0Dbm),
                    value = calibration.txPowerP0Dbm,
                    valueRange = CalibrationParams.MIN_P0_DBM..CalibrationParams.MAX_P0_DBM,
                    onValueChange = onTxPowerChange,
                    testTag = "slider_tx_power"
                )

                CalibrationSliderRow(
                    label = "Path Loss Exponent (n)",
                    formattedValue = String.format(Locale.US, "%.2f", calibration.pathLossExponentN),
                    value = calibration.pathLossExponentN,
                    valueRange = CalibrationParams.MIN_PATH_LOSS_N..CalibrationParams.MAX_PATH_LOSS_N,
                    onValueChange = onPathLossChange,
                    testTag = "slider_path_loss"
                )

                CalibrationSliderRow(
                    label = "Step Length (L_step)",
                    formattedValue = String.format(Locale.US, "%.2f m", calibration.stepLengthMeters),
                    value = calibration.stepLengthMeters,
                    valueRange = CalibrationParams.MIN_STEP_M..CalibrationParams.MAX_STEP_M,
                    onValueChange = onStepLengthChange,
                    testTag = "slider_step_length"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onRestoreDefaults,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("restore_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = null,
                            tint = ThermalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RESTORE DEFAULTS",
                            style = MaterialTheme.typography.labelMedium,
                            color = ThermalAmber
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetGroupHeader(
    title: String,
    badgeText: String,
    badgeColor: Color = ReadoutInkSecondary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(ObsidianBackground)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBackground,
        border = BorderStroke(1.dp, if (selected) accent else TacticalOutline),
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else ReadoutInkSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun CalibrationSliderRow(
    label: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ReadoutInkPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formattedValue,
                style = TelemetrySubValueStyle,
                color = ElectricCyan
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = ElectricCyan,
                activeTrackColor = ElectricCyan,
                inactiveTrackColor = TacticalOutline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .testTag(testTag)
        )
    }
}
