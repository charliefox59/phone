package app.olauncher.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val MINUTE = 60_000L
private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

suspend fun localWeather(context: Context): String? = withContext(Dispatchers.IO) {
    val cache = context.getSharedPreferences("local_weather", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val age = now - cache.getLong("observation", 0)
    if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        return@withContext null
    val manager = context.getSystemService(LocationManager::class.java)
    val provider = LocationManager.NETWORK_PROVIDER
    if (!manager.isProviderEnabled(provider)) return@withContext null
    val location = try {
        val last = manager.getLastKnownLocation(provider)
        if (last != null && (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000 in 0 until 15 * MINUTE) last
        else withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                    continuation.resume(it)
                }
            }
        }
    } catch (_: SecurityException) {
        null
    } ?: return@withContext null
    val latitude = (location.latitude * 10).roundToInt() / 10.0
    val longitude = (location.longitude * 10).roundToInt() / 10.0
    val area = "$latitude,$longitude"
    val sameArea = cache.getString("area", null) == area
    val cached = cache.getString("summary", null).takeIf { sameArea }
    if (sameArea && age in 0 until 45 * MINUTE) return@withContext cached
    if (cache.getString("attempt_area", null) != area || now - cache.getLong("attempt", 0) !in 0 until 5 * MINUTE) {
        cache.edit().putString("attempt_area", area).putLong("attempt", now).apply()
        val connection = URL("$ENDPOINT?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&timeformat=unixtime").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val current = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getJSONObject("current") }
            val observed = current.getLong("time") * 1000
            if (now - observed in -MINUTE until 3 * 60 * MINUTE) {
                val temperature = current.getDouble("temperature_2m").roundToInt()
                val condition = when (current.getInt("weather_code")) {
                    0 -> "clear"
                    1 -> "mostly clear"
                    2 -> "partly cloudy"
                    3 -> "overcast"
                    45, 48 -> "fog"
                    in 51..55 -> "drizzle"
                    56, 57 -> "freezing drizzle"
                    61 -> "light rain"
                    63 -> "rain"
                    65 -> "heavy rain"
                    66, 67 -> "freezing rain"
                    in 71..77 -> "snow"
                    in 80..82 -> "showers"
                    85, 86 -> "snow showers"
                    95, 96, 99 -> "thunderstorms"
                    else -> null
                }
                val summary = listOfNotNull("${temperature}°C", condition).joinToString(" · ")
                cache.edit().putString("area", area).putString("summary", summary).putLong("observation", observed).apply()
                return@withContext summary
            }
        } catch (_: IOException) {
            // Fall back to the recent forecast when offline.
        } catch (_: JSONException) {
            // Keep the recent forecast if the service returns incomplete data.
        } finally {
            connection.disconnect()
        }
    }
    cached.takeIf { age in 0 until 3 * 60 * MINUTE }
}
