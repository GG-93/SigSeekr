package com.example.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.dsp.BayesianGridFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

data class MapAnchorGps(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long
)

data class TileCoord(
    val z: Int,
    val x: Int,
    val y: Int
)

data class TileBounds(
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double
)

data class TileCacheState(
    val isDownloading: Boolean = false,
    val downloadedTiles: Int = 0,
    val totalTilesRequested: Int = 0,
    val cachedTileCount: Int = 0,
    val cacheSizeBytes: Long = 0L,
    val anchorGps: MapAnchorGps? = null,
    val lastError: String? = null
)

/**
 * Pure Kotlin + OkHttp Offline Slippy Map (`z/x/y`, zoom 16..18) PNG tile cache
 * backed by CartoDB Dark Matter tactical tiles in `context.filesDir/osm_tiles/`.
 *
 * INVARIANT: Never performs network requests automatically. Tiles are downloaded
 * ONLY when the user explicitly calls [cacheMapArea].
 */
class OfflineMapTileManager(
    context: Context,
    private val tileUrlTemplate: String = CARTO_DARK_MATTER_TEMPLATE
) {
    companion object {
        const val CARTO_DARK_MATTER_TEMPLATE =
            "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
        const val MIN_CACHE_ZOOM = 16
        const val MAX_CACHE_ZOOM = 18
        const val DEFAULT_CACHE_RADIUS_METERS = 200.0
        private const val EARTH_RADIUS_METERS = 6_378_137.0
        private const val MAX_LATITUDE_MERCATOR = 85.05112878
        private const val USER_AGENT = "SigSeeker/1.0 (Android Offline RF Radar)"

        /** Converts geographic (lat, lon) to Slippy Map tile indices (z, x, y). */
        fun latLonToTile(latDeg: Double, lonDeg: Double, zoom: Int): TileCoord {
            val clampedLat = latDeg.coerceIn(-MAX_LATITUDE_MERCATOR, MAX_LATITUDE_MERCATOR)
            val latRad = Math.toRadians(clampedLat)
            val n = 1 shl zoom
            val x = (((lonDeg + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
            val y = (((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0) * n)
                .toInt()
                .coerceIn(0, n - 1)
            return TileCoord(zoom, x, y)
        }

        /** Computes exact WGS84 bounding box for Slippy Map tile (z, x, y). */
        fun tileToLatLonBounds(z: Int, x: Int, y: Int): TileBounds {
            val n = (1 shl z).toDouble()
            val westLon = x / n * 360.0 - 180.0
            val eastLon = (x + 1) / n * 360.0 - 180.0
            val northLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
            val southLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (y + 1) / n))))
            return TileBounds(northLat, southLat, westLon, eastLon)
        }

        /** Web Mercator (EPSG:3857) forward projection in projected meters. */
        fun latLonToWebMercator(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
            val clampedLat = latDeg.coerceIn(-MAX_LATITUDE_MERCATOR, MAX_LATITUDE_MERCATOR)
            val x = EARTH_RADIUS_METERS * Math.toRadians(lonDeg)
            val y = EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + Math.toRadians(clampedLat) / 2.0))
            return x to y
        }

        /** Web Mercator (EPSG:3857) inverse projection to WGS84 (latDeg, lonDeg). */
        fun webMercatorToLatLon(mx: Double, my: Double): Pair<Double, Double> {
            val lonDeg = Math.toDegrees(mx / EARTH_RADIUS_METERS)
            val latDeg = Math.toDegrees(2.0 * atan(kotlin.math.exp(my / EARTH_RADIUS_METERS)) - PI / 2.0)
            return latDeg to lonDeg
        }

        /**
         * Projects a WGS84 (lat, lon) coordinate into SigSeeker's 50m x 50m
         * local Cartesian grid anchored with `(anchorLat, anchorLon)` at `(25.0m, 25.0m)`.
         */
        fun latLonToGridMeters(
            latDeg: Double,
            lonDeg: Double,
            anchorLat: Double,
            anchorLon: Double
        ): Pair<Double, Double> {
            val (mx0, my0) = latLonToWebMercator(anchorLat, anchorLon)
            val (mx, my) = latLonToWebMercator(latDeg, lonDeg)
            val scale = cos(Math.toRadians(anchorLat.coerceIn(-85.0, 85.0)))
            val halfArena = BayesianGridFilter.ARENA_SIZE_METERS * 0.5
            val gridX = halfArena + (mx - mx0) * scale
            val gridY = halfArena + (my - my0) * scale
            return gridX to gridY
        }

        /**
         * Inverse-projects a SigSeeker grid coordinate `(gridX, gridY)` back to
         * WGS84 `(latDeg, lonDeg)` relative to `(anchorLat, anchorLon)` at `(25.0m, 25.0m)`.
         */
        fun gridMetersToLatLon(
            gridX: Double,
            gridY: Double,
            anchorLat: Double,
            anchorLon: Double
        ): Pair<Double, Double> {
            val (mx0, my0) = latLonToWebMercator(anchorLat, anchorLon)
            val scale = cos(Math.toRadians(anchorLat.coerceIn(-85.0, 85.0))).coerceAtLeast(1e-6)
            val halfArena = BayesianGridFilter.ARENA_SIZE_METERS * 0.5
            val mx = mx0 + (gridX - halfArena) / scale
            val my = my0 + (gridY - halfArena) / scale
            return webMercatorToLatLon(mx, my)
        }

        /** Enumerates all Slippy Map tiles covering a square of `radiusMeters` around (lat, lon). */
        fun tilesForArea(
            centerLat: Double,
            centerLon: Double,
            radiusMeters: Double,
            zoomRange: IntRange = MIN_CACHE_ZOOM..MAX_CACHE_ZOOM
        ): List<TileCoord> {
            val scale = cos(Math.toRadians(centerLat.coerceIn(-85.0, 85.0))).coerceAtLeast(1e-6)
            val (mx0, my0) = latLonToWebMercator(centerLat, centerLon)
            val deltaMerc = radiusMeters / scale
            val (southLat, westLon) = webMercatorToLatLon(mx0 - deltaMerc, my0 - deltaMerc)
            val (northLat, eastLon) = webMercatorToLatLon(mx0 + deltaMerc, my0 + deltaMerc)

            val coords = mutableListOf<TileCoord>()
            for (z in zoomRange) {
                val topLeft = latLonToTile(northLat, westLon, z)
                val bottomRight = latLonToTile(southLat, eastLon, z)
                val xMin = minOf(topLeft.x, bottomRight.x)
                val xMax = maxOf(topLeft.x, bottomRight.x)
                val yMin = minOf(topLeft.y, bottomRight.y)
                val yMax = maxOf(topLeft.y, bottomRight.y)
                for (x in xMin..xMax) {
                    for (y in yMin..yMax) {
                        coords.add(TileCoord(z, x, y))
                    }
                }
            }
            return coords
        }
    }

    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.filesDir, "osm_tiles")
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val _cacheState = MutableStateFlow(TileCacheState())
    val cacheState: StateFlow<TileCacheState> = _cacheState.asStateFlow()

    fun tileFile(z: Int, x: Int, y: Int): File {
        return File(File(File(cacheRoot, z.toString()), x.toString()), "$y.png")
    }

    /** Loads a previously cached PNG tile from disk (100% offline, never touches network). */
    fun loadCachedTileBitmap(z: Int, x: Int, y: Int): Bitmap? {
        val file = tileFile(z, x, y)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the freshest available GPS/LocationManager fix without starting network calls. */
    @SuppressLint("MissingPermission")
    fun refreshAnchorFromLocationManager(): MapAnchorGps? {
        val lm = locationManager ?: return null
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val candidateProviders = buildList {
            if (hasFine) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        var bestLocation: Location? = null
        for (provider in candidateProviders) {
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (loc != null && (bestLocation == null || loc.time > bestLocation.time)) {
                bestLocation = loc
            }
        }

        val anchor = bestLocation?.let {
            MapAnchorGps(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracy,
                timestampMs = it.time
            )
        }
        _cacheState.value = _cacheState.value.copy(anchorGps = anchor)
        return anchor
    }

    suspend fun refreshCacheStats() = withContext(Dispatchers.IO) {
        val (count, bytes) = computeCacheFootprint()
        _cacheState.value = _cacheState.value.copy(
            cachedTileCount = count,
            cacheSizeBytes = bytes
        )
    }

    /**
     * Explicitly downloads CartoDB Dark Matter PNG tiles for zoom 16..18 around the
     * supplied GPS fix (or current LocationManager fix). Triggered ONLY by user action.
     */
    suspend fun cacheMapArea(
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusMeters: Double = DEFAULT_CACHE_RADIUS_METERS,
        zoomRange: IntRange = MIN_CACHE_ZOOM..MAX_CACHE_ZOOM
    ): Result<Int> = withContext(Dispatchers.IO) {
        val anchor = if (centerLat != null && centerLon != null) {
            MapAnchorGps(centerLat, centerLon, 0f, System.currentTimeMillis()).also {
                _cacheState.value = _cacheState.value.copy(anchorGps = it)
            }
        } else {
            refreshAnchorFromLocationManager()
        }

        if (anchor == null) {
            val msg = "No GPS fix available — move near a window or enable Location to cache tiles"
            _cacheState.value = _cacheState.value.copy(lastError = msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val tiles = tilesForArea(anchor.latitude, anchor.longitude, radiusMeters, zoomRange)
        _cacheState.value = _cacheState.value.copy(
            isDownloading = true,
            downloadedTiles = 0,
            totalTilesRequested = tiles.size,
            lastError = null
        )

        var successCount = 0
        var lastErr: String? = null

        try {
            for (coord in tiles) {
                val targetFile = tileFile(coord.z, coord.x, coord.y)
                if (targetFile.exists() && targetFile.length() > 0L) {
                    successCount++
                    _cacheState.value = _cacheState.value.copy(downloadedTiles = successCount)
                    continue
                }

                val url = tileUrlTemplate
                    .replace("{z}", coord.z.toString())
                    .replace("{x}", coord.x.toString())
                    .replace("{y}", coord.y.toString())

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                try {
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { fos ->
                                    fos.write(bytes)
                                }
                                successCount++
                            }
                        } else {
                            lastErr = "HTTP ${resp.code} fetching tile ${coord.z}/${coord.x}/${coord.y}"
                        }
                    }
                } catch (ioEx: Exception) {
                    lastErr = ioEx.message ?: "Offline or network unreachable"
                }

                _cacheState.value = _cacheState.value.copy(downloadedTiles = successCount)
            }
        } finally {
            val (count, bytes) = computeCacheFootprint()
            _cacheState.value = _cacheState.value.copy(
                isDownloading = false,
                cachedTileCount = count,
                cacheSizeBytes = bytes,
                lastError = if (successCount == 0) lastErr else null
            )
        }

        if (successCount > 0) {
            Result.success(successCount)
        } else {
            Result.failure(IllegalStateException(lastErr ?: "Failed to cache offline tiles"))
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        if (cacheRoot.exists()) {
            cacheRoot.deleteRecursively()
        }
        _cacheState.value = _cacheState.value.copy(
            cachedTileCount = 0,
            cacheSizeBytes = 0L,
            downloadedTiles = 0,
            totalTilesRequested = 0,
            lastError = null
        )
    }

    private fun computeCacheFootprint(): Pair<Int, Long> {
        if (!cacheRoot.exists()) return 0 to 0L
        var count = 0
        var bytes = 0L
        cacheRoot.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".png")) {
                count++
                bytes += file.length()
            }
        }
        return count to bytes
    }
}
