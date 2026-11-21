package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.dsp.CalibrationParams
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TelemetrySubValueStyle
import com.example.ui.theme.ThermalAmber
import java.util.Locale

/**
 * Collapsible Bottom Drawer for Live RF & Dead-Reckoning Calibration:
 * - Shield Drop A_max (5–30 dB, default 18 dB)
 * - Tx Power P0 (-70 to -20 dBm, default -45 dBm)
 * - Path Loss Exponent n (1.8–4.0, default 2.5)
 * - Step Stride Length L_step (0.50–1.00 m, default 0.72 m)
 */
@Composable
fun CalibrationDrawer(
    params: CalibrationParams,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onShieldDropChange: (Float) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onPathLossChange: (Float) -> Unit,
    onStepLengthChange: (Float) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(drawerShape)
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, drawerShape)
            .navigationBarsPadding()
    ) {
        // Collapsible Header Bar (>=48dp touch target)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RF & STEP CALIBRATION",
                    style = MaterialTheme.typography.labelLarge,
                    color = ReadoutInkPrimary
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "A_max %.0fdB · P0 %.0fdBm · n %.1f · Step %.2fm",
                        params.shieldDropDb,
                        params.txPowerP0Dbm,
                        params.pathLossExponentN,
                        params.stepLengthMeters
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkSecondary
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.cd_calibration_drawer),
                tint = ReadoutInkSecondary,
                modifier = Modifier.size(22.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ),
            exit = shrinkVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HorizontalDivider(color = TacticalOutline)

                CalibrationSliderRow(
                    label = "Shield Drop (A_max)",
                    formattedValue = String.format(Locale.US, "%.1f dB", params.shieldDropDb),
                    value = params.shieldDropDb,
                    valueRange = CalibrationParams.MIN_SHIELD_DB..CalibrationParams.MAX_SHIELD_DB,
                    accentColor = ThermalAmber,
                    testTag = "slider_shield_drop",
                    onValueChange = onShieldDropChange
                )

                CalibrationSliderRow(
                    label = "Tx Power Reference (P₀)",
                    formattedValue = String.format(Locale.US, "%.0f dBm", params.txPowerP0Dbm),
                    value = params.txPowerP0Dbm,
                    valueRange = CalibrationParams.MIN_P0_DBM..CalibrationParams.MAX_P0_DBM,
                    accentColor = ElectricCyan,
                    testTag = "slider_tx_power_p0",
                    onValueChange = onTxPowerChange
                )

                CalibrationSliderRow(
                    label = "Path Loss Exponent (n)",
                    formattedValue = String.format(Locale.US, "%.2f", params.pathLossExponentN),
                    value = params.pathLossExponentN,
                    valueRange = CalibrationParams.MIN_PATH_LOSS_N..CalibrationParams.MAX_PATH_LOSS_N,
                    accentColor = ElectricCyan,
                    testTag = "slider_path_loss_n",
                    onValueChange = onPathLossChange
                )

                CalibrationSliderRow(
                    label = "Step Stride Length (L_step)",
                    formattedValue = String.format(Locale.US, "%.2f m", params.stepLengthMeters),
                    value = params.stepLengthMeters,
                    valueRange = CalibrationParams.MIN_STEP_M..CalibrationParams.MAX_STEP_M,
                    accentColor = ElectricCyan,
                    testTag = "slider_step_length",
                    onValueChange = onStepLengthChange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onRestoreDefaults,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("calibration_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = null,
                            tint = ReadoutInkSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RESTORE DEFAULTS",
                            style = MaterialTheme.typography.labelMedium,
                            color = ReadoutInkSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationSliderRow(
    label: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: androidx.compose.ui.graphics.Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ReadoutInkSecondary
            )
            Text(
                text = formattedValue,
                style = TelemetrySubValueStyle,
                color = accentColor
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = TacticalOutline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}
