package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.ThermalYellow

enum class SetupHoldMode(
    val title: String,
    val subtitle: String,
    val shieldPreset: ShieldPreset,
    val screenOutwardMode: Boolean
) {
    HANDHELD_OMNI(
        title = "Handheld Omni 0dB",
        subtitle = "Default · Wide 360° scan & Wi-Fi RTT (no directional shield)",
        shieldPreset = ShieldPreset.OMNI_HANDHELD,
        screenOutwardMode = false
    ),
    BODY_CHEST(
        title = "Body/Chest 16dB",
        subtitle = "Upright against torso · Screen faces you (rear-camera −Z forward)",
        shieldPreset = ShieldPreset.POCKET_BODY,
        screenOutwardMode = false
    ),
    BREAST_POCKET_OUT(
        title = "Breast Pocket Screen-Out 16dB",
        subtitle = "Screen faces outward · Auto-enables +180° heading flip",
        shieldPreset = ShieldPreset.POCKET_BODY,
        screenOutwardMode = true
    ),
    CORNER_REFLECTOR(
        title = "90° Corner Reflector 27dB",
        subtitle = "Non-magnetic aluminum V-corner behind phone (tightest null)",
        shieldPreset = ShieldPreset.CORNER_REFLECTOR_90,
        screenOutwardMode = false
    )
}

/**
 * Interactive 3-step First-Run Walkthrough shown once on first-ever launch
 * (after permissions are granted, before the Sweep Setup Check starts radios).
 */
@Composable
fun FirstRunWalkthroughView(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val totalSteps = 3

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
                .padding(18.dp)
                .testTag("first_run_walkthrough"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top bar: step badge + skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (stepIndex) {
                            0 -> Icons.Filled.Tune
                            1 -> Icons.Filled.Shield
                            else -> Icons.Filled.Radar
                        },
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "SIGSEEKER BRIEFING · ${stepIndex + 1}/$totalSteps",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.testTag("walkthrough_skip_button")
                ) {
                    Text(
                        text = "SKIP",
                        style = MaterialTheme.typography.labelMedium,
                        color = ReadoutInkSecondary
                    )
                }
            }

            // Progress dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in 0 until totalSteps) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= stepIndex) ElectricCyan else TacticalOutline)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (stepIndex) {
                    0 -> WalkthroughStepControls()
                    1 -> WalkthroughStepPosture()
                    else -> WalkthroughStepTwoSpin()
                }
            }

            HorizontalDivider(color = TacticalOutline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (stepIndex > 0) {
                    OutlinedButton(
                        onClick = { stepIndex-- },
                        border = BorderStroke(1.dp, TacticalOutline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ReadoutInkPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("walkthrough_back_button")
                    ) {
                        Text("BACK", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Button(
                    onClick = {
                        if (stepIndex < totalSteps - 1) {
                            stepIndex++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .minimumInteractiveComponentSize()
                        .testTag("walkthrough_next_button")
                ) {
                    Text(
                        text = if (stepIndex < totalSteps - 1) "NEXT" else "CONTINUE TO SETUP",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkthroughStepControls() {
    Text(
        text = "Read-Only Telemetry vs. Tappable Controls",
        style = MaterialTheme.typography.titleMedium,
        color = ReadoutInkPrimary
    )
    Text(
        text = "SigSeeker separates live hardware health monitors from interactive tactical controls so you always know what reacts to a tap.",
        style = MaterialTheme.typography.bodySmall,
        color = ReadoutInkSecondary
    )
    WalkthroughInfoCard(
        title = "READ-ONLY STATUS INDICATORS",
        accent = SignalEmerald,
        body = "• MAG: 51µT — Live ambient magnetic flux (turns amber MAG WARP near steel/rebar).\n• RTT · BLE · IMU · STEP · MAG · USB — Hardware & GrapheneOS permission readiness dots.\n• EST DIST / LIVE / POSE / CONFIDENCE — Real-time numerical readouts."
    )
    WalkthroughInfoCard(
        title = "TAPPABLE CONTROLS",
        accent = ElectricCyan,
        body = "• Bg Pins: ON/OFF — Instantly hide or show passive background emitter shapes.\n• Carrying Mode & Screen Outward (+180°) — Switch RF shield attenuation or flip pocket heading.\n• SCAN SIGNALS (N) — Open the full Wi-Fi, BLE, RTT & Cell Towers scanner + dossiers.\n• RF ENV — Tap to reset GNSS jamming/spoofing baseline · Calibration drawer expands at bottom."
    )
}

@Composable
private fun WalkthroughStepPosture() {
    Text(
        text = "Carrying Posture & Tilt/Pocket Guard",
        style = MaterialTheme.typography.titleMedium,
        color = ReadoutInkPrimary
    )
    Text(
        text = "Your phone's heading arrow always points straight along your rear camera (−Z) when held upright so handheld, chest, and corner-reflector sweeps match where you are looking.",
        style = MaterialTheme.typography.bodySmall,
        color = ReadoutInkSecondary
    )
    WalkthroughInfoCard(
        title = "UPRIGHT & SCREEN-OUTWARD MODES",
        accent = ElectricCyan,
        body = "• Default (Rear Camera −Z): Hold upright with screen facing you — zero vertical gimbal lock.\n• Breast Pocket Screen-Out (+180°): Enable the Screen Outward toggle when placing the phone screen-facing-out against your chest."
    )
    WalkthroughInfoCard(
        title = "AUTOMATIC TILT GUARD (0 dB SAFETY)",
        accent = ThermalAmber,
        body = "• If the phone lies flat (|pitch| < 15°) or rolls sideways (|roll| > 45°), the Tilt Guard automatically forces shieldLoss = 0 dB and displays (GUARD 0dB) so false shadowing never corrupts the Bayesian heatmap.\n• Never use ferromagnetic steel as a shield — only your torso or non-magnetic aluminum."
    )
}

@Composable
private fun WalkthroughStepTwoSpin() {
    Text(
        text = "Two-Spin Triangulation & 6-Stage Shapes",
        style = MaterialTheme.typography.titleMedium,
        color = ReadoutInkPrimary
    )
    Text(
        text = "While stationary, emitters have unknown bearing and appear as Semicircles distributed around their estimated distance ring—never falsely stacked on one line.",
        style = MaterialTheme.typography.bodySmall,
        color = ReadoutInkSecondary
    )
    WalkthroughInfoCard(
        title = "THE TWO-SPIN TRIANGLE",
        accent = SignalEmerald,
        body = "1. Lock a target from SCAN SIGNALS or a radar shape dossier.\n2. Spin 270°–360° slowly at Anchor A (with Body or Corner shield) to establish bearing line A.\n3. Walk 5m perpendicular (±90°) along the glowing amber corridor to Anchor B.\n4. Spin 270°–360° at Anchor B — intersecting bearings collapse CEP95 ≤ 1.5m into a solid green/gold lock."
    )
    WalkthroughInfoCard(
        title = "PASSIVE SHAPE PROGRESSION",
        accent = ThermalYellow,
        body = "Background shapes only advance beyond Semicircle after ≥ 180° of heading sweep with ≥ 4 dB RSSI contrast (with shielding or ≥ 4m walked baseline in Omni mode). Tap SAVE & PIN to permanently pin a target in offline SQLite."
    )
}

@Composable
private fun WalkthroughInfoCard(
    title: String,
    accent: Color,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkPrimary
        )
    }
}

/**
 * Fast 1-card "Sweep Setup Check" modal shown on every fresh cold launch before
 * radio scanning and Bayesian grid ingestion begin.
 */
@Composable
fun SweepSetupCheckDialog(
    onStartSweep: (SetupHoldMode, EnvironmentPreset) -> Unit,
    onReplayWalkthrough: () -> Unit
) {
    var selectedHoldMode by remember { mutableStateOf(SetupHoldMode.HANDHELD_OMNI) }
    var selectedEnvironment by remember { mutableStateOf(EnvironmentPreset.INDOORS_DRYWALL) }

    Dialog(
        onDismissRequest = { /* Explicit START SWEEP required before radios start */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .border(1.dp, ElectricCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .testTag("sweep_setup_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElectricCyan.copy(alpha = 0.16f))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Radar,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SWEEP SETUP CHECK",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary
                        )
                        Text(
                            text = "Radios paused · Pick posture & environment to begin",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkSecondary
                        )
                    }
                    TextButton(
                        onClick = onReplayWalkthrough,
                        modifier = Modifier.testTag("setup_replay_walkthrough_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "Walkthrough",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "GUIDE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }

                HorizontalDivider(color = TacticalOutline)

                // 1. Hold Mode selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "1. HOLD / SHIELD MODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    SetupHoldMode.entries.forEach { mode ->
                        val isSelected = selectedHoldMode == mode
                        val accent = if (mode.screenOutwardMode) ThermalAmber else ElectricCyan
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) accent.copy(alpha = 0.16f) else ElevatedSurface)
                                .border(
                                    1.dp,
                                    if (isSelected) accent else TacticalOutline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedHoldMode = mode }
                                .padding(horizontal = 11.dp, vertical = 9.dp)
                                .testTag("setup_hold_${mode.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Explore,
                                contentDescription = null,
                                tint = if (isSelected) accent else ReadoutInkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) ReadoutInkPrimary else ReadoutInkSecondary
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ReadoutInkMuted
                                )
                            }
                        }
                    }
                }

                // 2. Environment selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "2. RF ENVIRONMENT (PATH LOSS n & STRIDE)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalEmerald
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val envOptions = listOf(
                            EnvironmentPreset.INDOORS_DRYWALL to "Indoors Drywall",
                            EnvironmentPreset.OUTDOORS_OPEN to "Outdoors Open",
                            EnvironmentPreset.DENSE_CONCRETE to "Concrete"
                        )
                        envOptions.forEach { (env, shortTitle) ->
                            val isSelected = selectedEnvironment == env
                            Surface(
                                onClick = { selectedEnvironment = env },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) SignalEmerald.copy(alpha = 0.20f) else ElevatedSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) SignalEmerald else TacticalOutline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("setup_env_${env.name.lowercase()}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = shortTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) SignalEmerald else ReadoutInkPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "n=${env.pathLossExponentN} · ${env.stepLengthMeters}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ReadoutInkMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Prominent START SWEEP primary button
                Button(
                    onClick = { onStartSweep(selectedHoldMode, selectedEnvironment) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_sweep_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "START SWEEP",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}
