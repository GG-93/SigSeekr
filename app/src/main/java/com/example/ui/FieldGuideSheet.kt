package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.dsp.EmitterShapeStage
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ReadoutInkMuted
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TacticalCrimson
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.ThermalYellow

private enum class FieldGuideTab(val label: String) {
    CARRYING("Carrying & Shield"),
    TRIANGULATION("Triangulation"),
    SHAPES_ALERTS("6 Shapes & Alerts"),
    PRESETS_PHYSICS("Presets & Physics")
}

/**
 * 4-Tab Offline Tactical Field Guide opened from the Top HUD `?` icon.
 */
@Composable
fun FieldGuideSheet(
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(FieldGuideTab.CARRYING) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.86f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
                .testTag("field_guide_sheet"),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SIGSEEKER FIELD GUIDE",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary
                        )
                        Text(
                            text = "100% offline RF direction-finding manual",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("field_guide_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = ReadoutInkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4-Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FieldGuideTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Surface(
                            onClick = { activeTab = tab },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) ElectricCyan.copy(alpha = 0.22f) else ElevatedSurface,
                            border = BorderStroke(1.dp, if (selected) ElectricCyan else TacticalOutline),
                            modifier = Modifier.testTag("guide_tab_${tab.name.lowercase()}")
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) ElectricCyan else ReadoutInkSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = TacticalOutline)

                // Tab Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (activeTab) {
                        FieldGuideTab.CARRYING -> CarryingGuideContent()
                        FieldGuideTab.TRIANGULATION -> TriangulationGuideContent()
                        FieldGuideTab.SHAPES_ALERTS -> ShapesAndAlertsGuideContent()
                        FieldGuideTab.PRESETS_PHYSICS -> PresetsPhysicsGuideContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun CarryingGuideContent() {
    GuideSectionCard(
        title = "1. OMNI / HANDHELD (0 dB)",
        accent = ElectricCyan,
        body = "Hold the phone flat in front of you with no shielding. Best for quick 360° wide-area scanning and Wi-Fi RTT (802.11mc/az) ranging, where distance rings localize without requiring body attenuation."
    )
    GuideSectionCard(
        title = "2. BREAST POCKET / BODY SHIELD (16 dB)",
        accent = SignalEmerald,
        body = "Place the phone vertically against your chest or breast pocket with the screen facing OUTWARD away from your torso. Water in the human body absorbs 2.4/5 GHz RF behind you by ~16 dB, turning your body into a passive directional shield."
    )
    GuideSectionCard(
        title = "3. FLAT ALUMINUM PLATE (22 dB)",
        accent = ThermalAmber,
        body = "Hold a non-magnetic aluminum sheet or foil-wrapped clipboard 2–4 cm directly behind the phone's rear cover. Creates a crisp ~22 dB rear null without warping the internal magnetometer."
    )
    GuideSectionCard(
        title = "4. 90° ALUMINUM CORNER REFLECTOR (27 dB)",
        accent = ThermalYellow,
        body = "Fold non-magnetic aluminum foil/sheet into a 90° V-corner behind the phone's antennas (~3 cm spacing). Delivers ~27 dB front-to-back ratio for tight indoor room-level convergence."
    )
    GuideSectionCard(
        title = "MAGNETIC WARP WARNING (NEVER USE STEEL)",
        accent = TacticalCrimson,
        body = "Never use steel baking sheets, iron plates, or magnetic cases as an RF shield. Ferromagnetic metal distorts Earth's 25–70 µT magnetic field, triggering the amber MAG WARP pill and corrupting compass bearing math. Always use non-magnetic aluminum or your torso."
    )
}

@Composable
private fun TriangulationGuideContent() {
    GuideSectionCard(
        title = "STEP 1 · SPIN 360° AT ANCHOR A",
        accent = ElectricCyan,
        body = "Lock a single emitter from the Scanner or a Radar Shape Dossier. Stand at Anchor A and rotate slowly through at least 270°–360° (5–8 seconds) while holding your shield steady. Watch the circular spin ring fill and establish the dotted bearing line from Anchor A."
    )
    GuideSectionCard(
        title = "STEP 2 · WALK 5m PERPENDICULAR (±90°)",
        accent = ThermalAmber,
        body = "Once Spin 1 completes, glowing amber corridor arrows appear ±90° sideways to the target bearing. Walk 5.0 meters along either corridor while keeping the phone upright so the hardware step detector tracks your Anchor A → Anchor B baseline."
    )
    GuideSectionCard(
        title = "STEP 3 · SPIN 360° AT ANCHOR B",
        accent = ElectricCyan,
        body = "At Anchor B, perform a second slow 360° spin. The second dotted bearing line intersects the first bearing line, collapsing the Bayesian probability cloud to a tight Thermal Plasma hotspot."
    )
    GuideSectionCard(
        title = "STEP 4 · TRIANGULATION LOCKED (CEP95 ≤ 1.5m)",
        accent = SignalEmerald,
        body = "When 95% of the posterior probability mass fits inside a 1.5m radius (CEP95 ≤ 1.5m), the triangulation triangle illuminates solid emerald/gold. Tap SAVE SNAP to store the coordinates in SQLite and permanently PIN the target on the map."
    )
}

@Composable
private fun ShapesAndAlertsGuideContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "6-STAGE BACKGROUND EMITTER GEOMETRY",
            style = MaterialTheme.typography.labelLarge,
            color = ElectricCyan
        )
        Text(
            text = "Every passive Wi-Fi/BLE emitter evolves through 6 shapes as your spins narrow its strong-signal angular spread:",
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkSecondary
        )

        val rows = listOf(
            Triple(EmitterShapeStage.SEMICIRCLE, "180° · Fresh sighting, wide bearing uncertainty", ElectricCyan),
            Triple(EmitterShapeStage.TRIANGLE, "120° · Initial directional quadrant isolated", ElectricCyan),
            Triple(EmitterShapeStage.SQUARE, "90° · Mid-resolution directional lobe", ThermalAmber),
            Triple(EmitterShapeStage.PENTAGON, "45° · Narrowed bearing sector", ThermalAmber),
            Triple(EmitterShapeStage.HEXAGON, "20° · High-confidence bearing & range convergence", SignalEmerald),
            Triple(EmitterShapeStage.PINNED, "PINNED · Saved SQLite target (emerald/gold circle)", ThermalYellow)
        )

        rows.forEach { (stage, desc, tint) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBackground)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    drawGeometricStageMarker(
                        stage = stage,
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.42f,
                        color = tint
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stage.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadoutInkSecondary
                    )
                }
            }
        }
    }

    GuideSectionCard(
        title = "MAGNETIC FLUX PILL (MAG µT)",
        accent = ThermalAmber,
        body = "Monitors ambient magnetic field |B| = √(Bx²+By²+Bz²). Normal Earth field is 25–70 µT (emerald). Values outside 25–70 µT flash MAG WARP — step away from rebar, vehicles, speakers, or steel desks."
    )
    GuideSectionCard(
        title = "GNSS RF ENVIRONMENT PILL (AGC & C/N0)",
        accent = TacticalCrimson,
        body = "Runs Android's 10-epoch vs 50-epoch differential GNSS AGC & C/N0 classifier. Simultaneous AGC + C/N0 drop flags JAMMING; AGC drop paired with C/N0 surge flags SPOOFING."
    )
    GuideSectionCard(
        title = "CELLULAR TIMING ADVANCE & 2G STINGRAY WATCH",
        accent = SignalEmerald,
        body = "Open Scanner → Cell Towers to inspect serving/neighbor PCI, RSRP, SINR, and LTE/GSM Timing Advance range rings. Grant optional Phone State inside the Cell Towers tab to activate 2G GSM downgrade detection."
    )
}

@Composable
private fun PresetsPhysicsGuideContent() {
    GuideSectionCard(
        title = "SHIELDED CARDIOID ATTENUATION MODEL",
        accent = ElectricCyan,
        body = "Expected RSSI at cell (x, y) is modeled as:\nRSSI_exp = P₀ − 10·n·log₁₀(d) − A_max · (1 − cos Δθ) / 2\nwhere Δθ is the angle between your forward heading and the candidate cell. Turning your back on an emitter produces maximum attenuation A_max."
    )
    GuideSectionCard(
        title = "EMITTER CLASS & BLE 0x0A AUTO-CALIBRATION",
        accent = SignalEmerald,
        body = "• BLE Tag / AirTag: P₀ = −58 dBm\n• Smartphone / Laptop / Wearable: P₀ = −48 dBm\n• Wi-Fi Router / Hotspot: P₀ = −40 dBm\nWhenever a locked BLE target advertises explicit AD Type 0x0A TX Power, SigSeeker automatically overrides P₀ and displays a 0x0A AUTO badge."
    )
    GuideSectionCard(
        title = "ENVIRONMENT PATH LOSS (n) & STRIDE (L_step)",
        accent = ThermalAmber,
        body = "• Outdoors / Open Park: n = 2.0, L_step = 0.75 m\n• Indoors / Drywall House: n = 2.6, L_step = 0.65 m\n• Dense Concrete / Commercial: n = 3.4, L_step = 0.62 m\nMatch the preset to your surroundings so radial range estimates stay accurate."
    )
    GuideSectionCard(
        title = "TIER 1 WI-FI RTT (802.11mc / 802.11az)",
        accent = SignalEmerald,
        body = "Access points marked [RTT] support Fine Timing Measurement (~0.8m σ). SigSeeker fuses RTT range rings directly into the Bayesian grid, achieving ~1m lock even without heavy shielding."
    )
}

@Composable
private fun GuideSectionCard(
    title: String,
    accent: Color,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = accent
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkSecondary
        )
    }
}
