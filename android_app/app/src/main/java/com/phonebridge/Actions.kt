package com.phonebridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Network work the board can't do itself. Everything here runs on the phone. */
object Actions {

    data class Weather(val temp: String, val desc: String, val place: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Open-Meteo: free, no API key. */
    suspend fun weather(city: String): Weather = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(city, "UTF-8")
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=$q"))
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) throw RuntimeException("city \"$city\" not found")
        val place = results.getJSONObject(0)
        val wx = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.getDouble("latitude")}" +
                    "&longitude=${place.getDouble("longitude")}&current=temperature_2m,weather_code"
            )
        ).getJSONObject("current")
        Weather(
            temp = "${wx.getDouble("temperature_2m").roundToInt()}°C",
            desc = wmo(wx.getInt("weather_code")),
            place = place.getString("name"),
        )
    }

    private fun wmo(c: Int): String = when {
        c == 0 -> "Clear"
        c <= 3 -> "Partly cloudy"
        c <= 48 -> "Fog"
        c <= 57 -> "Drizzle"
        c <= 67 -> "Rain"
        c <= 77 -> "Snow"
        c <= 82 -> "Showers"
        c <= 86 -> "Snow showers"
        else -> "Thunderstorm"
    }
}
