package app.olauncher.helper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val MINUTE = 60_000L
private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast?latitude=51.51&longitude=-0.13&current=temperature_2m,weather_code&timeformat=unixtime"

suspend fun londonWeather(context: Context): String? = withContext(Dispatchers.IO) {
    val cache = context.getSharedPreferences("london_weather", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val age = now - cache.getLong("observation", 0)
    val cached = cache.getString("summary", null)
    if (age in 0 until 45 * MINUTE) return@withContext cached
    if (now - cache.getLong("attempt", 0) !in 0 until 5 * MINUTE) {
        cache.edit().putLong("attempt", now).apply()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
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
                val summary = listOfNotNull("london", "${temperature}°C", condition).joinToString(" · ")
                cache.edit().putString("summary", summary).putLong("observation", observed).apply()
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
