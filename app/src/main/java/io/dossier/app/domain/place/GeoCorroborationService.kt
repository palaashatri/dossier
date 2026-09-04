package io.dossier.app.domain.place

import io.dossier.app.data.place.ExifParser
import io.dossier.app.domain.analysis.GeoTemporalAnalyzer
import io.dossier.app.domain.analysis.SolarPosition
import io.dossier.app.domain.model.ReverseImageLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Corroborates EXIF-supplied coordinates/time for user-selected media.
 *
 * This service does not infer a person's location from identity data. It accepts
 * coordinates already embedded in the selected media, reverse-geocodes that point,
 * retrieves a bounded historical-weather slice, and computes solar geometry locally.
 * Only coordinates/date are sent; image bytes and identity fields are never uploaded.
 * Temporal analysis is withheld unless EXIF carries the GPS UTC date/time pair.
 */
class GeoCorroborationService {
    data class WeatherSnapshot(
        val timeUtc: String,
        val temperatureC: Double?,
        val cloudCoverPercent: Double?,
        val precipitationMm: Double?,
        val weatherCode: Int?
    )

    data class Result(
        val displayName: String?,
        val latitude: Double,
        val longitude: Double,
        val capturedAtUtcMillis: Long?,
        val weather: WeatherSnapshot?,
        val solar: SolarPosition?,
        val evidence: List<ReverseImageLookupResult.WebEvidence>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun corroborate(metadata: ExifParser.Metadata): Result? = withContext(Dispatchers.IO) {
        val lat = metadata.latitude ?: return@withContext null
        val lon = metadata.longitude ?: return@withContext null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@withContext null
        val capturedMillis = parseGpsUtc(metadata.gpsDateStamp, metadata.gpsTimeStamp)

        coroutineScope {
            val placeDeferred = async { reverseGeocode(lat, lon) }
            val weatherDeferred = if (capturedMillis != null) async { historicalWeather(lat, lon, capturedMillis) } else null
            val displayName = placeDeferred.await()
            val weather = weatherDeferred?.await()
            val solar = capturedMillis?.let { GeoTemporalAnalyzer.solarPosition(lat, lon, it) }

            val evidence = buildList {
                if (displayName != null) {
                    add(
                        ReverseImageLookupResult.WebEvidence(
                            title = "OpenStreetMap coordinate corroboration",
                            snippet = "EXIF coordinates reverse-geocode to ${displayName.take(180)}.",
                            url = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon",
                            origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                        )
                    )
                }
                if (capturedMillis == null && !metadata.capturedAt.isNullOrBlank()) {
                    add(
                        ReverseImageLookupResult.WebEvidence(
                            title = "Temporal corroboration withheld",
                            snippet = "The image contains a camera-local capture time but no unambiguous GPS UTC date/time. Dossier will not guess a timezone for weather or shadow analysis.",
                            url = "https://exiftool.org/TagNames/GPS.html",
                            origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                        )
                    )
                }
                if (weather != null) {
                    add(
                        ReverseImageLookupResult.WebEvidence(
                            title = "Historical weather corroboration",
                            snippet = buildString {
                                append("Nearest hourly reanalysis at ${weather.timeUtc}: ")
                                weather.temperatureC?.let { append("${format1(it)}°C; ") }
                                weather.cloudCoverPercent?.let { append("cloud ${format0(it)}%; ") }
                                weather.precipitationMm?.let { append("precipitation ${format1(it)} mm; ") }
                                weather.weatherCode?.let { append("weather code $it.") }
                            }.trim().take(220),
                            url = "https://open-meteo.com/en/docs/historical-weather-api",
                            origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                        )
                    )
                }
                if (solar != null) {
                    add(
                        ReverseImageLookupResult.WebEvidence(
                            title = "Solar / shadow geometry",
                            snippet = buildString {
                                append("At the EXIF GPS UTC coordinate/time, computed sun azimuth ${format1(solar.azimuthDegrees)}°, ")
                                append("elevation ${format1(solar.elevationDegrees)}°, expected shadow bearing ${format1(solar.approximateShadowBearingDegrees)}°")
                                solar.shadowLengthToObjectHeightRatio?.let { append(", shadow/object-height ratio ≈ ${format2(it)}") }
                                append(". Use only as temporal corroboration, not identity/location proof.")
                            }.take(270),
                            url = "https://gml.noaa.gov/grad/solcalc/calcdetails.html",
                            origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                        )
                    )
                }
            }

            Result(displayName, lat, lon, capturedMillis, weather, solar, evidence)
        }
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? = runCatching {
        val request = Request.Builder()
            .url("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.take(MAX_RESPONSE_CHARS) ?: return null
            val root = JSON.parseToJsonElement(body) as? JsonObject ?: return null
            root.string("display_name")?.take(MAX_PLACE_CHARS)
        }
    }.getOrNull()

    private fun historicalWeather(latitude: Double, longitude: Double, timestampMillis: Long): WeatherSnapshot? = runCatching {
        val utc = java.time.Instant.ofEpochMilli(timestampMillis).atOffset(ZoneOffset.UTC)
        val date = utc.toLocalDate().toString()
        val url = buildString {
            append("https://archive-api.open-meteo.com/v1/archive")
            append("?latitude=$latitude&longitude=$longitude")
            append("&start_date=$date&end_date=$date")
            append("&hourly=temperature_2m,cloud_cover,precipitation,weather_code")
            append("&timezone=UTC")
        }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.take(MAX_RESPONSE_CHARS) ?: return null
            val root = JSON.parseToJsonElement(body) as? JsonObject ?: return null
            val hourly = root["hourly"] as? JsonObject ?: return null
            val times = hourly.stringArray("time")
            if (times.isEmpty()) return null
            val targetHour = utc.withMinute(0).withSecond(0).withNano(0).toLocalDateTime().toString().take(13)
            val index = times.indices.minByOrNull { idx ->
                if (times[idx].startsWith(targetHour)) 0 else kotlin.math.abs(idx - utc.hour)
            } ?: return null
            WeatherSnapshot(
                timeUtc = times.getOrNull(index) ?: return null,
                temperatureC = hourly.doubleArray("temperature_2m").getOrNull(index),
                cloudCoverPercent = hourly.doubleArray("cloud_cover").getOrNull(index),
                precipitationMm = hourly.doubleArray("precipitation").getOrNull(index),
                weatherCode = hourly.intArray("weather_code").getOrNull(index)
            )
        }
    }.getOrNull()

    internal fun parseGpsUtc(dateStamp: String?, timeStamp: String?): Long? {
        val dateRaw = dateStamp?.trim()?.takeIf(String::isNotBlank) ?: return null
        val timeRaw = timeStamp?.trim()?.takeIf(String::isNotBlank) ?: return null
        val date = runCatching {
            LocalDate.parse(dateRaw.replace('-', ':'), DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT))
        }.getOrNull() ?: return null
        val time = parseGpsClock(timeRaw) ?: return null
        return date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private fun parseGpsClock(raw: String): LocalTime? {
        val simple = runCatching {
            LocalTime.parse(raw, DateTimeFormatter.ofPattern("H:mm:ss", Locale.ROOT))
        }.getOrNull()
        if (simple != null) return simple

        val parts = raw.split(',').map(String::trim)
        if (parts.size != 3) return null
        val values = parts.mapNotNull(::parseRational)
        if (values.size != 3) return null
        val hour = values[0].toInt()
        val minute = values[1].toInt()
        val second = values[2].toInt().coerceIn(0, 59)
        return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
    }

    private fun parseRational(value: String): Double? {
        value.toDoubleOrNull()?.let { return it }
        val split = value.split('/')
        if (split.size != 2) return null
        val numerator = split[0].trim().toDoubleOrNull() ?: return null
        val denominator = split[1].trim().toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return numerator / denominator
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.doubleArray(key: String): List<Double?> =
        (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }

    private fun JsonObject.intArray(key: String): List<Int?> =
        (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }

    private fun format0(value: Double): String = "%.0f".format(Locale.US, value)
    private fun format1(value: Double): String = "%.1f".format(Locale.US, value)
    private fun format2(value: Double): String = "%.2f".format(Locale.US, value)

    private companion object {
        const val USER_AGENT = "Dossier/0.1 authorized-self-audit"
        const val MAX_RESPONSE_CHARS = 500_000
        const val MAX_PLACE_CHARS = 240
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
