package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.dsp.BackgroundEmitter
import com.example.dsp.BayesianGridFilter
import com.example.dsp.EmitterShapeStage
import com.example.dsp.EmitterTransport
import com.example.dsp.GridSnapshot
import com.example.dsp.SweepCoachState
import com.example.dsp.SweepStage
import com.example.dsp.UserPose
import com.example.maps.OfflineMapTileManager
import com.example.maps.TileBounds
import com.example.maps.TileCacheState
import com.example.maps.TileCoord
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private data class LoadedMapTile(
    val coord: TileCoord,
    val bounds: TileBounds,
    val imageBitmap: ImageBitmap
)

/**
 * High-performance 100x100 Bayesian Thermal Plasma Radar Canvas layered over:
 * 1. Optional Offline CartoDB Dark Matter Slippy Map PNG tiles (tactical dark filter)
 * 2. 5m tactical range rings & grid lines
 * 3. Pre-allocated 100x100 ARGB_8888 Thermal Plasma posterior heatmap
 * 4. Guided Two-Spin Triangulation Triangle overlay (Anchor A/B, dotted bearings,
 *    perpendicular walk corridor arrow, solid emerald/gold lock triangle)
 * 5. 6-Stage Background Emitter shapes (Semicircle -> Hexagon -> Pinned) with tap hit-testing
 * 6. User dead-reckoning trail, heading cone, shield null arc, and target peak reticle
 */
@Composable
fun RadarCanvas(
    gridSnapshot: GridSnapshot,
    userPose: UserPose,
    hasLockedTarget: Boolean,
    lockedTargetMac: String?,
    lockedTargetFingerprint: String?,
    lockedTargetLabel: String?,
    backgroundEmitters: List<BackgroundEmitter>,
    showBackgroundPins: Boolean,
    sweepCoachState: SweepCoachState,
    tileCacheState: TileCacheState,
    mapOverlayEnabled: Boolean,
    offlineMapTileManager: OfflineMapTileManager,
    onTapBackgroundEmitter: (BackgroundEmitter) -> Unit,
    modifier: Modifier = Modifier
) {
    // Reusable ARGB_8888 bitmap & pixel array (zero per-frame heap churn)
    val gridSize = BayesianGridFilter.GRID_SIZE
    val pixelBuffer = remember { IntArray(BayesianGridFilter.TOTAL_CELLS) }
    val backingBitmap = remember {
        Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888)
    }

    val imageBitmap = remember(gridSnapshot.sequenceId, hasLockedTarget) {
        val probs = gridSnapshot.probabilities
        val maxProb = gridSnapshot.maxProbability.coerceAtLeast(1e-9f)
        val uniformThreshold = 1.0f / BayesianGridFilter.TOTAL_CELLS
        val hasSignal = hasLockedTarget && gridSnapshot.updateCount > 0L && maxProb > uniformThreshold * 1.15f

        if (!hasSignal) {
            pixelBuffer.fill(0x00000000)
        } else {
            val lut = ThermalPlasmaLut.argbLut
            for (y in 0 until gridSize) {
                val bitmapRow = gridSize - 1 - y
                val srcRowOffset = y * gridSize
                val dstRowOffset = bitmapRow * gridSize
                for (x in 0 until gridSize) {
                    val norm = (probs[srcRowOffset + x] / maxProb).coerceIn(0f, 1f)
                    val lutIndex = (norm.toDouble().pow(0.75) * 255.0).toInt().coerceIn(0, 255)
                    pixelBuffer[dstRowOffset + x] = lut[lutIndex]
                }
            }
        }
        backingBitmap.setPixels(pixelBuffer, 0, gridSize, 0, 0, gridSize, gridSize)
        backingBitmap.asImageBitmap()
    }

    // Load cached offline OSM tiles off the main thread whenever anchor/cache count changes
    var loadedTiles by remember { mutableStateOf<List<LoadedMapTile>>(emptyList()) }
    val anchorGps = tileCacheState.anchorGps

    LaunchedEffect(mapOverlayEnabled, anchorGps, tileCacheState.cachedTileCount) {
        if (!mapOverlayEnabled || anchorGps == null || tileCacheState.cachedTileCount <= 0) {
            loadedTiles = emptyList()
            return@LaunchedEffect
        }
        loadedTiles = withContext(Dispatchers.IO) {
            loadVisibleOfflineTiles(
                manager = offlineMapTileManager,
                anchorLat = anchorGps.latitude,
                anchorLon = anchorGps.longitude
            )
        }
    }

    // Dark tactical color matrix filter for map tiles so neon RF overlays stay ultra-legible
    val mapColorFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.55f, 0f, 0f, 0f, -10f,
                    0f, 0.65f, 0f, 0f, -6f,
                    0f, 0f, 0.80f, 0f, 4f,
                    0f, 0f, 0f, 0.72f, 0f
                )
            )
        )
    }

    var legendExpanded by remember { mutableStateOf(false) }
    val tapRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianBackground)
            .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
            .testTag("radar_canvas_container")
            .semantics {
                contentDescription = if (hasLockedTarget) {
                    "RF probability radar tracking ${lockedTargetLabel ?: "target"} at " +
                        String.format(Locale.US, "%.1f meters", gridSnapshot.estimatedDistanceMeters)
                } else {
                    "RF radar showing ${backgroundEmitters.size} background emitters. Tap a shape to inspect."
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(backgroundEmitters, showBackgroundPins) {
                    detectTapGestures { tapOffset ->
                        if (!showBackgroundPins || backgroundEmitters.isEmpty()) return@detectTapGestures
                        val arenaSize = BayesianGridFilter.ARENA_SIZE_METERS.toFloat()
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectTapGestures

                        var nearest: BackgroundEmitter? = null
                        var bestDist = tapRadiusPx
                        for (emitter in backgroundEmitters) {
                            val ex = (emitter.estimatedX / arenaSize).coerceIn(0f, 1f) * width
                            val ey = (1f - (emitter.estimatedY / arenaSize).coerceIn(0f, 1f)) * height
                            val d = hypot(tapOffset.x - ex, tapOffset.y - ey)
                            if (d <= bestDist) {
                                bestDist = d
                                nearest = emitter
                            }
                        }
                        if (nearest != null) {
                            onTapBackgroundEmitter(nearest)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val arenaSize = BayesianGridFilter.ARENA_SIZE_METERS.toFloat()

            fun worldToPx(wx: Double, wy: Double): Offset {
                val px = ((wx / arenaSize).coerceIn(-0.5, 1.5) * w).toFloat()
                val py = ((1.0 - (wy / arenaSize).coerceIn(-0.5, 1.5)) * h).toFloat()
                return Offset(px, py)
            }

            // 1. Offline CartoDB Dark Matter Slippy Map Tiles (when enabled & cached)
            if (mapOverlayEnabled && anchorGps != null && loadedTiles.isNotEmpty()) {
                for (tile in loadedTiles) {
                    val (westX, northY) = OfflineMapTileManager.latLonToGridMeters(
                        latDeg = tile.bounds.northLat,
                        lonDeg = tile.bounds.westLon,
                        anchorLat = anchorGps.latitude,
                        anchorLon = anchorGps.longitude
                    )
                    val (eastX, southY) = OfflineMapTileManager.latLonToGridMeters(
                        latDeg = tile.bounds.southLat,
                        lonDeg = tile.bounds.eastLon,
                        anchorLat = anchorGps.latitude,
                        anchorLon = anchorGps.longitude
                    )
                    val leftPx = ((westX / arenaSize) * w).roundToInt()
                    val rightPx = ((eastX / arenaSize) * w).roundToInt()
                    val topPx = ((1.0 - northY / arenaSize) * h).roundToInt()
                    val bottomPx = ((1.0 - southY / arenaSize) * h).roundToInt()
                    val dstW = (rightPx - leftPx).coerceAtLeast(1)
                    val dstH = (bottomPx - topPx).coerceAtLeast(1)

                    drawImage(
                        image = tile.imageBitmap,
                        dstOffset = IntOffset(leftPx, topPx),
                        dstSize = IntSize(dstW, dstH),
                        colorFilter = mapColorFilter,
                        filterQuality = FilterQuality.Low
                    )
                }
            }

            // 2. 5m Tactical Grid Lines
            val gridSteps = 10
            for (i in 1 until gridSteps) {
                val frac = i.toFloat() / gridSteps
                val stroke = if (i == 5) 1.5f else 0.8f
                val color = if (i == 5) TacticalOutline else TacticalGridLine.copy(alpha = 0.72f)
                drawLine(color = color, start = Offset(w * frac, 0f), end = Offset(w * frac, h), strokeWidth = stroke)
                drawLine(color = color, start = Offset(0f, h * frac), end = Offset(w, h * frac), strokeWidth = stroke)
            }

            // 3. Concentric Range Rings (5m, 10m, 15m, 20m around center)
            val centerPx = Offset(w * 0.5f, h * 0.5f)
            val metersToPx = w / arenaSize
            val ringStroke = Stroke(
                width = 1.1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            )
            for (radiusMeters in listOf(5f, 10f, 15f, 20f)) {
                drawCircle(
                    color = TacticalOutline.copy(alpha = 0.65f),
                    radius = radiusMeters * metersToPx,
                    center = centerPx,
                    style = ringStroke
                )
            }

            // 4. Thermal Plasma Bayesian Probability Heatmap
            if (hasLockedTarget && gridSnapshot.updateCount > 0L) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.Medium
                )
            }

            // 5. Guided Two-Spin Triangulation Triangle Overlay
            if (hasLockedTarget && sweepCoachState.stage != SweepStage.IDLE) {
                drawTriangulationOverlay(
                    coach = sweepCoachState,
                    userPose = userPose,
                    gridSnapshot = gridSnapshot,
                    metersToPx = metersToPx,
                    worldToPx = ::worldToPx
                )
            }

            // 6. 6-Stage Background Emitter Shapes (Semicircle -> Hexagon -> Pinned)
            if (showBackgroundPins) {
                for (emitter in backgroundEmitters) {
                    val isCurrentlyLocked = (lockedTargetMac != null && emitter.latestMacAddress == lockedTargetMac) ||
                        (lockedTargetFingerprint != null && emitter.fingerprintHash == lockedTargetFingerprint)
                    val pos = worldToPx(emitter.estimatedX.toDouble(), emitter.estimatedY.toDouble())
                    drawBackgroundEmitterShape(
                        emitter = emitter,
                        center = pos,
                        isLocked = isCurrentlyLocked
                    )
                }
            }

            // 7. User Dead-Reckoning Trail
            val trail = userPose.trail
            if (trail.size >= 2) {
                val trailPath = Path()
                val first = worldToPx(trail.first().x, trail.first().y)
                trailPath.moveTo(first.x, first.y)
                for (i in 1 until trail.size) {
                    val pt = worldToPx(trail[i].x, trail[i].y)
                    trailPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = trailPath,
                    color = ObsidianBackground.copy(alpha = 0.80f),
                    style = Stroke(width = 6.5f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = trailPath,
                    color = ElectricCyan.copy(alpha = 0.85f),
                    style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                )
            }

            // 8. Estimated Target Peak Crosshair Reticle
            if (hasLockedTarget && gridSnapshot.updateCount > 0L) {
                val peakPx = worldToPx(
                    gridSnapshot.estimatedTargetX.toDouble(),
                    gridSnapshot.estimatedTargetY.toDouble()
                )
                val lockColor = if (sweepCoachState.stage == SweepStage.LOCKED) SignalEmerald else ThermalYellow
                val outerR = 16f
                drawCircle(
                    color = lockColor.copy(alpha = 0.95f),
                    radius = outerR,
                    center = peakPx,
                    style = Stroke(width = 2.4f)
                )
                drawLine(lockColor, Offset(peakPx.x - outerR - 6f, peakPx.y), Offset(peakPx.x - 5f, peakPx.y), 2.2f)
                drawLine(lockColor, Offset(peakPx.x + 5f, peakPx.y), Offset(peakPx.x + outerR + 6f, peakPx.y), 2.2f)
                drawLine(lockColor, Offset(peakPx.x, peakPx.y - outerR - 6f), Offset(peakPx.x, peakPx.y - 5f), 2.2f)
                drawLine(lockColor, Offset(peakPx.x, peakPx.y + 5f), Offset(peakPx.x, peakPx.y + outerR + 6f), 2.2f)
                drawCircle(color = lockColor, radius = 3f, center = peakPx)
            }

            // 9. TOP Z-LAYER (DRAWN LAST, ABOVE ALL SHAPES & HEATMAPS):
            //    High-Visibility Player Reticle, 2x Cyan Chevron, Contrast Halo & 35% Dashed Forward Ray
            val userPx = worldToPx(userPose.xMeters, userPose.yMeters)
            val theta = userPose.headingRad
            val fx = cos(theta).toFloat()
            val fy = (-sin(theta)).toFloat()
            val rx = sin(theta).toFloat()
            val ry = cos(theta).toFloat()

            // Translucent forward FOV cone
            val coneLength = (w * 0.5f) * 0.22f
            val halfConeRad = Math.toRadians(22.0)
            val coneTipLeft = Offset(
                x = userPx.x + (coneLength * cos(theta + halfConeRad)).toFloat(),
                y = userPx.y - (coneLength * sin(theta + halfConeRad)).toFloat()
            )
            val coneTipRight = Offset(
                x = userPx.x + (coneLength * cos(theta - halfConeRad)).toFloat(),
                y = userPx.y - (coneLength * sin(theta - halfConeRad)).toFloat()
            )
            val conePath = Path().apply {
                moveTo(userPx.x, userPx.y)
                lineTo(coneTipLeft.x, coneTipLeft.y)
                lineTo(coneTipRight.x, coneTipRight.y)
                close()
            }
            drawPath(path = conePath, color = ElectricCyan.copy(alpha = 0.22f))

            // Crisp dashed forward heading ray extending 35% of radar radius with dark contrast halo
            val rayLength = (w * 0.5f) * 0.35f
            val rayEnd = Offset(
                x = userPx.x + fx * rayLength,
                y = userPx.y + fy * rayLength
            )
            val rayDash = PathEffect.dashPathEffect(floatArrayOf(14f, 9f), 0f)
            drawLine(
                color = ObsidianBackground.copy(alpha = 0.92f),
                start = userPx,
                end = rayEnd,
                strokeWidth = 7.5f,
                cap = StrokeCap.Round,
                pathEffect = rayDash
            )
            drawLine(
                color = ElectricCyan,
                start = userPx,
                end = rayEnd,
                strokeWidth = 3.4f,
                cap = StrokeCap.Round,
                pathEffect = rayDash
            )
            // Small bright terminal pip at tip of forward ray
            drawCircle(color = ObsidianBackground.copy(alpha = 0.92f), radius = 6.5f, center = rayEnd)
            drawCircle(color = ElectricCyan, radius = 3.8f, center = rayEnd)

            // Rear shield null arc (behind user at theta + pi) with contrast halo
            val screenHeadingDeg = (-Math.toDegrees(theta)).toFloat()
            val shieldArcRadius = 34f
            drawArc(
                color = ObsidianBackground.copy(alpha = 0.90f),
                startAngle = screenHeadingDeg + 180f - 45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(userPx.x - shieldArcRadius, userPx.y - shieldArcRadius),
                size = Size(shieldArcRadius * 2f, shieldArcRadius * 2f),
                style = Stroke(width = 8.0f, cap = StrokeCap.Round)
            )
            drawArc(
                color = ThermalAmber.copy(alpha = 0.95f),
                startAngle = screenHeadingDeg + 180f - 45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(userPx.x - shieldArcRadius, userPx.y - shieldArcRadius),
                size = Size(shieldArcRadius * 2f, shieldArcRadius * 2f),
                style = Stroke(width = 4.0f, cap = StrokeCap.Round)
            )

            // Dark contrast halo backing circle beneath player chevron
            drawCircle(
                color = ObsidianBackground.copy(alpha = 0.88f),
                radius = 28f,
                center = userPx
            )
            drawCircle(
                color = ElectricCyan.copy(alpha = 0.35f),
                radius = 28f,
                center = userPx,
                style = Stroke(width = 2.0f)
            )

            // 2x larger high-contrast directional cyan chevron with dark contrast outline
            val tip = Offset(userPx.x + fx * 26f, userPx.y + fy * 26f)
            val leftWing = Offset(userPx.x - fx * 16f - rx * 19f, userPx.y - fy * 16f - ry * 19f)
            val notch = Offset(userPx.x - fx * 7f, userPx.y - fy * 7f)
            val rightWing = Offset(userPx.x - fx * 16f + rx * 19f, userPx.y - fy * 16f + ry * 19f)

            val chevronPath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(rightWing.x, rightWing.y)
                lineTo(notch.x, notch.y)
                lineTo(leftWing.x, leftWing.y)
                close()
            }
            // Dark contrast outline halo around chevron
            drawPath(
                path = chevronPath,
                color = ObsidianBackground,
                style = Stroke(width = 9.0f, join = StrokeJoin.Round, cap = StrokeCap.Round)
            )
            // High-contrast filled cyan chevron + bright white-cyan rim
            drawPath(path = chevronPath, color = ElectricCyan)
            drawPath(
                path = chevronPath,
                color = ReadoutInkPrimary.copy(alpha = 0.92f),
                style = Stroke(width = 2.2f, join = StrokeJoin.Round, cap = StrokeCap.Round)
            )
            // Crisp center anchor pip
            drawCircle(color = ObsidianBackground, radius = 4.2f, center = userPx)
            drawCircle(color = ReadoutInkPrimary, radius = 2.2f, center = userPx)
        }

        // Cardinal Compass Labels (N / S / E / W)
        Text(
            text = "N",
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan,
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

        // Top-left scale + active emitter count badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CharcoalSurface.copy(alpha = 0.86f))
                .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "50×50m",
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary
            )
            Text(
                text = if (showBackgroundPins) "${backgroundEmitters.size} RF" else "${backgroundEmitters.size} RF (OFF)",
                style = MaterialTheme.typography.labelSmall,
                color = if (showBackgroundPins) ElectricCyan else ReadoutInkMuted
            )
        }

        // Top-right thermal plasma legend / map status badge
        if (hasLockedTarget) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CharcoalSurface.copy(alpha = 0.86f))
                    .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ThermalYellow)
                )
                Text(
                    text = "CEP95 ${String.format(Locale.US, "%.1fm", gridSnapshot.cep95Meters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gridSnapshot.cep95Meters <= 1.5f) SignalEmerald else ThermalYellow
                )
            }
        } else if (mapOverlayEnabled && loadedTiles.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CharcoalSurface.copy(alpha = 0.86f))
                    .border(1.dp, SignalEmerald.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "OSM ${loadedTiles.size}T",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalEmerald
                )
            }
        }

        // Bottom-left compact 6-Shape Corner Legend (expandable)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CharcoalSurface.copy(alpha = 0.90f))
                .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                .clickable { legendExpanded = !legendExpanded }
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("radar_shape_legend_toggle")
        ) {
            Text(
                text = if (legendExpanded) "6-STAGE SHAPES ▾" else "SHAPES ▸",
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkPrimary
            )
            if (legendExpanded) {
                Spacer(modifier = Modifier.size(3.dp))
                LegendShapeRow(EmitterShapeStage.SEMICIRCLE, "180°", ElectricCyan)
                LegendShapeRow(EmitterShapeStage.TRIANGLE, "120°", ElectricCyan)
                LegendShapeRow(EmitterShapeStage.SQUARE, "90°", ThermalAmber)
                LegendShapeRow(EmitterShapeStage.PENTAGON, "45°", ThermalAmber)
                LegendShapeRow(EmitterShapeStage.HEXAGON, "20°", SignalEmerald)
                LegendShapeRow(EmitterShapeStage.PINNED, "PIN", ThermalYellow)
            }
        }

        // Bottom-right hint when no target is locked
        if (!hasLockedTarget) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CharcoalSurface.copy(alpha = 0.86f))
                    .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "TAP SHAPE → DOSSIER",
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkSecondary
                )
            }
        }
    }
}

@Composable
private fun LegendShapeRow(
    stage: EmitterShapeStage,
    band: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawGeometricStageMarker(
                stage = stage,
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension * 0.44f,
                color = tint,
                isLocked = false
            )
        }
        Text(
            text = "${stage.label} ($band)",
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkSecondary
        )
    }
}

private fun DrawScope.drawTriangulationOverlay(
    coach: SweepCoachState,
    userPose: UserPose,
    gridSnapshot: GridSnapshot,
    metersToPx: Float,
    worldToPx: (Double, Double) -> Offset
) {
    val userPx = worldToPx(userPose.xMeters, userPose.yMeters)
    val targetPx = worldToPx(
        gridSnapshot.estimatedTargetX.toDouble(),
        gridSnapshot.estimatedTargetY.toDouble()
    )
    val anchorAPx = coach.anchorA?.let { worldToPx(it.x, it.y) }
    val anchorBPx = coach.anchorB?.let { worldToPx(it.x, it.y) }

    val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
    val isLocked = coach.stage == SweepStage.LOCKED

    // 1. Circular Spin Progress Ring around user during SPIN_1 or SPIN_2
    if (coach.stage == SweepStage.SPIN_1 || coach.stage == SweepStage.SPIN_2) {
        val ringR = 34f
        drawCircle(
            color = TacticalOutline.copy(alpha = 0.85f),
            radius = ringR,
            center = userPx,
            style = Stroke(width = 3.5f)
        )
        val sweepDeg = (coach.progressFraction.coerceIn(0f, 1f) * 360f)
        drawArc(
            color = ElectricCyan,
            startAngle = -90f,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(userPx.x - ringR, userPx.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(width = 4.2f, cap = StrokeCap.Round)
        )
    }

    // 2. Perpendicular Walk Corridor Arrow during WALK_PERPENDICULAR
    if (coach.stage == SweepStage.WALK_PERPENDICULAR && anchorAPx != null) {
        val corridorLenPx = coach.targetWalkMeters * metersToPx
        val bearings = listOfNotNull(coach.perpendicularWalkBearingARad, coach.perpendicularWalkBearingBRad)
        for (bRad in bearings) {
            val endPt = Offset(
                x = anchorAPx.x + (corridorLenPx * cos(bRad)).toFloat(),
                y = anchorAPx.y - (corridorLenPx * sin(bRad)).toFloat()
            )
            // Glowing corridor halo + arrow shaft
            drawLine(
                color = ThermalAmber.copy(alpha = 0.22f),
                start = anchorAPx,
                end = endPt,
                strokeWidth = 14f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = ThermalAmber,
                start = anchorAPx,
                end = endPt,
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
            // Arrowhead chevrons
            val headLen = 14f
            val leftWing = Offset(
                x = endPt.x - (headLen * cos(bRad - 0.5)).toFloat(),
                y = endPt.y + (headLen * sin(bRad - 0.5)).toFloat()
            )
            val rightWing = Offset(
                x = endPt.x - (headLen * cos(bRad + 0.5)).toFloat(),
                y = endPt.y + (headLen * sin(bRad + 0.5)).toFloat()
            )
            drawLine(ThermalAmber, endPt, leftWing, 3f, StrokeCap.Round)
            drawLine(ThermalAmber, endPt, rightWing, 3f, StrokeCap.Round)
        }
    }

    // 3. Anchor A -> Target bearing line (dotted while sweeping, solid glowing emerald/gold when LOCKED)
    if (anchorAPx != null) {
        val edgeColor = if (isLocked) SignalEmerald else ElectricCyan.copy(alpha = 0.85f)
        if (isLocked) {
            drawLine(
                color = SignalEmerald.copy(alpha = 0.26f),
                start = anchorAPx,
                end = targetPx,
                strokeWidth = 9f,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = edgeColor,
            start = anchorAPx,
            end = targetPx,
            strokeWidth = if (isLocked) 3.2f else 2.2f,
            pathEffect = if (isLocked) null else dottedEffect
        )
    }

    // 4. Solid Baseline Path from Anchor A -> Anchor B (or current user position during walk)
    if (anchorAPx != null) {
        val baselineEnd = anchorBPx ?: if (coach.stage == SweepStage.WALK_PERPENDICULAR) userPx else null
        if (baselineEnd != null) {
            val baseColor = if (isLocked) ThermalYellow else ElectricCyan
            if (isLocked) {
                drawLine(
                    color = ThermalYellow.copy(alpha = 0.24f),
                    start = anchorAPx,
                    end = baselineEnd,
                    strokeWidth = 9f,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = baseColor,
                start = anchorAPx,
                end = baselineEnd,
                strokeWidth = if (isLocked) 3.4f else 2.8f,
                cap = StrokeCap.Round
            )
        }
    }

    // 5. Anchor B -> Target second bearing line
    if (anchorBPx != null) {
        val edgeColor = if (isLocked) SignalEmerald else ThermalAmber.copy(alpha = 0.90f)
        if (isLocked) {
            drawLine(
                color = SignalEmerald.copy(alpha = 0.26f),
                start = anchorBPx,
                end = targetPx,
                strokeWidth = 9f,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = edgeColor,
            start = anchorBPx,
            end = targetPx,
            strokeWidth = if (isLocked) 3.2f else 2.2f,
            pathEffect = if (isLocked) null else dottedEffect
        )
    }

    // 6. Anchor A and Anchor B Diamond Pins
    if (anchorAPx != null) {
        drawAnchorPin(center = anchorAPx, fill = ElectricCyan)
    }
    if (anchorBPx != null) {
        drawAnchorPin(center = anchorBPx, fill = if (isLocked) ThermalYellow else ThermalAmber)
    }
}

private fun DrawScope.drawAnchorPin(center: Offset, fill: Color) {
    val r = 7.5f
    val diamond = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r, center.y)
        lineTo(center.x, center.y + r)
        lineTo(center.x - r, center.y)
        close()
    }
    drawPath(diamond, color = fill.copy(alpha = 0.35f))
    drawPath(diamond, color = fill, style = Stroke(width = 2.2f))
    drawCircle(color = ObsidianBackground, radius = 2.2f, center = center)
}

private fun DrawScope.drawBackgroundEmitterShape(
    emitter: BackgroundEmitter,
    center: Offset,
    isLocked: Boolean
) {
    val baseColor = when {
        emitter.shapeStage == EmitterShapeStage.PINNED -> SignalEmerald
        emitter.trackerTag?.isPotentialUnwantedTracker == true -> TacticalCrimson
        emitter.isRttResponder -> SignalEmerald
        emitter.transport == EmitterTransport.BLE -> ElectricCyan
        else -> ThermalAmber
    }
    val radius = when (emitter.shapeStage) {
        EmitterShapeStage.PINNED -> 12f
        EmitterShapeStage.HEXAGON -> 10.5f
        else -> 9.5f
    }

    drawGeometricStageMarker(
        stage = emitter.shapeStage,
        center = center,
        radius = radius,
        color = baseColor,
        isLocked = isLocked
    )
}

/**
 * Draws the exact 6-stage geometric shape for an emitter on either the radar canvas
 * or the Field Guide / legend mini-preview.
 */
fun DrawScope.drawGeometricStageMarker(
    stage: EmitterShapeStage,
    center: Offset,
    radius: Float,
    color: Color,
    isLocked: Boolean = false
) {
    if (isLocked) {
        drawCircle(
            color = ThermalYellow.copy(alpha = 0.28f),
            radius = radius + 6f,
            center = center
        )
    }

    when (stage) {
        EmitterShapeStage.SEMICIRCLE -> {
            drawArc(
                color = color.copy(alpha = 0.30f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f)
            )
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 2.0f)
            )
        }

        EmitterShapeStage.TRIANGLE -> {
            drawRegularPolygon(center, radius, sides = 3, color = color)
        }

        EmitterShapeStage.SQUARE -> {
            drawRegularPolygon(center, radius, sides = 4, color = color, rotationOffsetRad = PI / 4.0)
        }

        EmitterShapeStage.PENTAGON -> {
            drawRegularPolygon(center, radius, sides = 5, color = color)
        }

        EmitterShapeStage.HEXAGON -> {
            drawRegularPolygon(center, radius, sides = 6, color = color)
        }

        EmitterShapeStage.PINNED -> {
            // Permanent glowing emerald & gold concentric target circle
            drawCircle(color = SignalEmerald.copy(alpha = 0.25f), radius = radius + 4f, center = center)
            drawCircle(color = SignalEmerald, radius = radius, center = center, style = Stroke(width = 2.4f))
            drawCircle(color = ThermalYellow, radius = radius * 0.52f, center = center, style = Stroke(width = 1.8f))
            drawCircle(color = ThermalYellow, radius = 2.5f, center = center)
        }
    }
}

private fun DrawScope.drawRegularPolygon(
    center: Offset,
    radius: Float,
    sides: Int,
    color: Color,
    rotationOffsetRad: Double = -PI / 2.0
) {
    val path = Path()
    for (i in 0 until sides) {
        val angle = rotationOffsetRad + (2.0 * PI * i / sides)
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path = path, color = color.copy(alpha = 0.28f))
    drawPath(path = path, color = color, style = Stroke(width = 2.1f))
}

/**
 * Loads cached offline Slippy Map PNG tiles covering the 50m x 50m radar arena.
 * Prefers zoom 18 for crisp street/building footprint detail, falling back to z17 / z16.
 */
private fun loadVisibleOfflineTiles(
    manager: OfflineMapTileManager,
    anchorLat: Double,
    anchorLon: Double
): List<LoadedMapTile> {
    val arena = BayesianGridFilter.ARENA_SIZE_METERS
    val (southLat, westLon) = OfflineMapTileManager.gridMetersToLatLon(0.0, 0.0, anchorLat, anchorLon)
    val (northLat, eastLon) = OfflineMapTileManager.gridMetersToLatLon(arena, arena, anchorLat, anchorLon)

    for (zoom in listOf(18, 17, 16)) {
        val topLeft = OfflineMapTileManager.latLonToTile(northLat, westLon, zoom)
        val bottomRight = OfflineMapTileManager.latLonToTile(southLat, eastLon, zoom)
        val xMin = minOf(topLeft.x, bottomRight.x)
        val xMax = maxOf(topLeft.x, bottomRight.x)
        val yMin = minOf(topLeft.y, bottomRight.y)
        val yMax = maxOf(topLeft.y, bottomRight.y)

        val loaded = mutableListOf<LoadedMapTile>()
        for (x in xMin..xMax) {
            for (y in yMin..yMax) {
                val bmp = manager.loadCachedTileBitmap(zoom, x, y) ?: continue
                loaded.add(
                    LoadedMapTile(
                        coord = TileCoord(zoom, x, y),
                        bounds = OfflineMapTileManager.tileToLatLonBounds(zoom, x, y),
                        imageBitmap = bmp.asImageBitmap()
                    )
                )
            }
        }
        if (loaded.isNotEmpty()) return loaded
    }
    return emptyList()
}
