package com.example.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dsp.BayesianGridFilter
import com.example.dsp.GridSnapshot
import com.example.dsp.LocalizationTier
import com.example.dsp.UserPose
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ReadoutInkMuted
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TacticalCrimson
import com.example.ui.theme.TacticalGridLine
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.ThermalPlasmaLut
import com.example.ui.theme.ThermalYellow
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 50m x 50m (100x100 cells @ 0.5m) Dark Tactical RF Radar Canvas.
 *
 * Uses a pre-allocated 100x100 ARGB Bitmap + IntArray mapped through
 * [ThermalPlasmaLut] so 10-15 FPS redraws perform zero heap allocations per frame.
 */
@Composable
fun RadarCanvas(
    snapshot: GridSnapshot,
    userPose: UserPose,
    lockedTargetMac: String?,
    isTargetStale: Boolean,
    shieldDropDb: Float,
    modifier: Modifier = Modifier
) {
    // Reusable pixel buffer & 100x100 heatmap bitmap (allocated once)
    val pixelBuffer = remember { IntArray(BayesianGridFilter.TOTAL_CELLS) }
    val heatmapBitmap = remember {
        Bitmap.createBitmap(
            BayesianGridFilter.GRID_SIZE,
            BayesianGridFilter.GRID_SIZE,
            Bitmap.Config.ARGB_8888
        )
    }

    // Populate bitmap whenever an immutable GridSnapshot arrives
    val imageBitmap = remember(snapshot.sequenceId, snapshot.updateCount) {
        val probs = snapshot.probabilities
        val maxP = snapshot.maxProbability
        val uniformP = BayesianGridFilter.UNIFORM_PROB.toFloat()
        val showHeat = snapshot.updateCount > 0L && maxP > uniformP * 1.15f
        val lut = ThermalPlasmaLut.argbLut
        val gridSize = BayesianGridFilter.GRID_SIZE

        if (!showHeat) {
            pixelBuffer.fill(0x00000000)
        } else {
            val invMax = 1.0f / maxP.coerceAtLeast(1e-12f)
            for (yIdx in 0 until gridSize) {
                // Flip Y so Cartesian +Y (North = 50m) renders at top row 0
                val bitmapRow = (gridSize - 1) - yIdx
                val srcRowOffset = yIdx * gridSize
                val dstRowOffset = bitmapRow * gridSize
                for (xIdx in 0 until gridSize) {
                    val p = probs[srcRowOffset + xIdx]
                    val normalized = (p * invMax).coerceIn(0f, 1f)
                    // Slight gamma lift (sqrt) reveals secondary contours clearly on dark obsidian
                    val perceptual = sqrt(normalized)
                    val lutIdx = (perceptual * 255f).toInt().coerceIn(0, 255)
                    pixelBuffer[dstRowOffset + xIdx] = lut[lutIdx]
                }
            }
        }
        heatmapBitmap.setPixels(pixelBuffer, 0, gridSize, 0, 0, gridSize, gridSize)
        heatmapBitmap.asImageBitmap()
    }

    val pulseTransition = rememberInfiniteTransition(label = "target_pulse")
    val targetPulse by pulseTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val arenaSize = BayesianGridFilter.ARENA_SIZE_METERS.toFloat()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianBackground)
            .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
            .testTag("radar_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pxPerMeterX = w / arenaSize
            val pxPerMeterY = h / arenaSize

            fun worldToCanvas(wx: Float, wy: Float): Offset {
                return Offset(
                    x = (wx / arenaSize) * w,
                    y = (1f - (wy / arenaSize)) * h
                )
            }

            // 1. Subtle radial tactical vignette
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF101826), ObsidianBackground),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.72f
                )
            )

            // 2. Metric 5m Cartesian gridlines (10 divisions across 50m)
            for (step in 1 until 10) {
                val frac = step / 10f
                val isCenterAxis = step == 5
                val lineColor = if (isCenterAxis) TacticalOutline else TacticalGridLine
                val strokeWidth = if (isCenterAxis) 1.4.dp.toPx() else 0.75.dp.toPx()

                drawLine(
                    color = lineColor,
                    start = Offset(w * frac, 0f),
                    end = Offset(w * frac, h),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, h * frac),
                    end = Offset(w, h * frac),
                    strokeWidth = strokeWidth
                )
            }

            // 3. Thermal Plasma 100x100 probability heatmap layer
            if (snapshot.updateCount > 0L) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low
                )
            }

            val userPx = worldToCanvas(userPose.xMeters.toFloat(), userPose.yMeters.toFloat())

            // 4. Concentric range rings around operator (5m, 10m, 15m)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            for (radiusM in listOf(5f, 10f, 15f)) {
                drawCircle(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    radius = radiusM * pxPerMeterX,
                    center = userPx,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dashEffect)
                )
            }

            // 5. Walking dead-reckoning breadcrumb trail
            val trail = userPose.trail
            if (trail.size > 1) {
                for (i in 1 until trail.size) {
                    val p0 = worldToCanvas(trail[i - 1].x.toFloat(), trail[i - 1].y.toFloat())
                    val p1 = worldToCanvas(trail[i].x.toFloat(), trail[i].y.toFloat())
                    val ageAlpha = (0.15f + 0.75f * (i.toFloat() / trail.size.toFloat()))
                    drawLine(
                        color = ElectricCyan.copy(alpha = ageAlpha),
                        start = p0,
                        end = p1,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 6. Cardioid Forward Sensitivity Cone & Rear Shield Null Arc
            // Screen angle for mathematical heading theta: screenDeg = -toDegrees(theta)
            val headingRad = userPose.headingRad.toFloat()
            val screenHeadingDeg = -Math.toDegrees(userPose.headingRad).toFloat()
            val coneRadius = 5.5f * pxPerMeterX

            // Forward open lobe (cyan translucent wedge)
            drawArc(
                color = ElectricCyan.copy(alpha = 0.14f),
                startAngle = screenHeadingDeg - 42f,
                sweepAngle = 84f,
                useCenter = true,
                topLeft = Offset(userPx.x - coneRadius, userPx.y - coneRadius),
                size = Size(coneRadius * 2f, coneRadius * 2f)
            )

            // Rear body/aluminum shield attenuation arc (amber/crimson indicator)
            val shieldRadius = 2.6f * pxPerMeterX
            val shieldAlpha = ((shieldDropDb - 5f) / 25f).coerceIn(0.25f, 0.9f)
            drawArc(
                color = ThermalAmber.copy(alpha = shieldAlpha),
                startAngle = screenHeadingDeg + 125f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(userPx.x - shieldRadius, userPx.y - shieldRadius),
                size = Size(shieldRadius * 2f, shieldRadius * 2f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 7. Vector line & Pulsing Crosshair to Estimated Target Peak
            if (lockedTargetMac != null && snapshot.updateCount > 0L) {
                val targetPx = worldToCanvas(snapshot.estimatedTargetX, snapshot.estimatedTargetY)
                val targetColor = if (isTargetStale) {
                    ThermalAmber
                } else if (snapshot.activeTier == LocalizationTier.RTT_TIER1) {
                    SignalEmerald
                } else {
                    ThermalYellow
                }

                // Dashed range vector from operator to estimated target
                drawLine(
                    color = targetColor.copy(alpha = 0.55f),
                    start = userPx,
                    end = targetPx,
                    strokeWidth = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )

                // Outer pulsing acquisition ring
                val ringRadius = (10.dp.toPx()) * (if (isTargetStale) 1.0f else targetPulse)
                drawCircle(
                    color = targetColor.copy(alpha = 0.35f),
                    radius = ringRadius,
                    center = targetPx,
                    style = Stroke(width = 1.6.dp.toPx())
                )

                // Crisp tactical crosshair reticle
                val crossLen = 9.dp.toPx()
                val gap = 3.dp.toPx()
                val crossStroke = 2.dp.toPx()
                drawLine(
                    targetColor,
                    Offset(targetPx.x - crossLen, targetPx.y),
                    Offset(targetPx.x - gap, targetPx.y),
                    strokeWidth = crossStroke
                )
                drawLine(
                    targetColor,
                    Offset(targetPx.x + gap, targetPx.y),
                    Offset(targetPx.x + crossLen, targetPx.y),
                    strokeWidth = crossStroke
                )
                drawLine(
                    targetColor,
                    Offset(targetPx.x, targetPx.y - crossLen),
                    Offset(targetPx.x, targetPx.y - gap),
                    strokeWidth = crossStroke
                )
                drawLine(
                    targetColor,
                    Offset(targetPx.x, targetPx.y + gap),
                    Offset(targetPx.x, targetPx.y + crossLen),
                    strokeWidth = crossStroke
                )
                drawCircle(
                    color = targetColor,
                    radius = 2.5.dp.toPx(),
                    center = targetPx
                )
            }

            // 8. Operator Pose Chevron pointing along Cartesian heading theta
            // World vector (cos(theta), sin(theta)) -> Screen vector (+cos(theta), -sin(theta))
            val dirX = cos(headingRad)
            val dirY = -sin(headingRad)
            val perpX = -dirY
            val perpY = dirX

            val tipLen = 10.dp.toPx()
            val tailLen = 6.dp.toPx()
            val wingHalf = 6.5.dp.toPx()

            val chevron = Path().apply {
                moveTo(userPx.x + dirX * tipLen, userPx.y + dirY * tipLen)
                lineTo(
                    userPx.x - dirX * tailLen + perpX * wingHalf,
                    userPx.y - dirY * tailLen + perpY * wingHalf
                )
                lineTo(userPx.x - dirX * (tailLen * 0.35f), userPx.y - dirY * (tailLen * 0.35f))
                lineTo(
                    userPx.x - dirX * tailLen - perpX * wingHalf,
                    userPx.y - dirY * tailLen - perpY * wingHalf
                )
                close()
            }
            drawPath(path = chevron, color = ElectricCyan)
            drawPath(
                path = chevron,
                color = ObsidianBackground,
                style = Stroke(width = 1.2.dp.toPx())
            )
        }

        // Cardinal Compass Overlays
        Text(
            text = if (userPose.isHeadingZeroed) "N (ZEROED)" else "N",
            style = MaterialTheme.typography.labelSmall,
            color = if (userPose.isHeadingZeroed) ThermalAmber else ElectricCyan,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
        )
        Text(
            text = "S",
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
        )
        Text(
            text = "W",
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp)
        )
        Text(
            text = "E",
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
        )

        // Top-Left Active Tier & Grid Resolution Pill
        val tierColor = when {
            lockedTargetMac == null -> ReadoutInkMuted
            isTargetStale -> TacticalCrimson
            snapshot.activeTier == LocalizationTier.RTT_TIER1 -> SignalEmerald
            snapshot.activeTier == LocalizationTier.SHIELD_TIER2 -> ThermalAmber
            else -> ElectricCyan
        }
        val tierText = when {
            lockedTargetMac == null -> "50×50m · 0.5m/CELL"
            isTargetStale -> "LOCKED · SIGNAL STALE"
            snapshot.updateCount == 0L -> "LOCKED · AWAITING PKTS"
            else -> snapshot.activeTier.displayLabel
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(CharcoalSurface.copy(alpha = 0.86f), RoundedCornerShape(6.dp))
                .border(1.dp, tierColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = tierText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = tierColor
            )
        }

        // Top-Right Estimated Coordinates Badge (when active)
        if (lockedTargetMac != null && snapshot.updateCount > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(CharcoalSurface.copy(alpha = 0.86f), RoundedCornerShape(6.dp))
                    .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "EST %.1f, %.1fm",
                        snapshot.estimatedTargetX,
                        snapshot.estimatedTargetY
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ThermalYellow
                )
            }
        }

        // Bottom-Right Thermal Plasma Intensity Scale Legend
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(CharcoalSurface.copy(alpha = 0.84f), RoundedCornerShape(6.dp))
                .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = "P(x,y)",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = ReadoutInkSecondary
            )
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF0D0887),
                                Color(0xFF9C179E),
                                Color(0xFFED7953),
                                ThermalYellow
                            )
                        )
                    )
            )
        }

        // Bottom-Left Shield Drop Indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(CharcoalSurface.copy(alpha = 0.84f), RoundedCornerShape(6.dp))
                .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = String.format(Locale.US, "SHIELD -%.0fdB", shieldDropDb),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = ThermalAmber
            )
        }

        // Empty guidance chip when no target is locked
        if (lockedTargetMac == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .background(CharcoalSurface.copy(alpha = 0.90f), RoundedCornerShape(8.dp))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Tap TARGET above to lock a single Wi-Fi or BLE emitter",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadoutInkPrimary
                )
            }
        }
    }
}
