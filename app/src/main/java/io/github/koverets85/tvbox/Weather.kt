package io.github.koverets85.tvbox

import android.content.Context
import org.json.JSONObject

/** Free weather from Open-Meteo (no key). Cached for 30 minutes. Default city: Moscow. */
object Weather {
    data class Day(val name: String, val icon: String, val max: Int, val min: Int)
    data class Now(val temp: Int, val feels: Int, val wind: Int, val icon: String, val sky: String, val days: List<Day>)

    @Volatile var last: Now? = null
    private var at = 0L

    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀" to "ясно"
        1, 2 -> "⛅" to "переменная облачность"
        3 -> "☁" to "облачно"
        45, 48 -> "🌫" to "туман"
        in 51..67 -> "🌧" to "дождь"
        in 71..77, 85, 86 -> "🌨" to "снег"
        in 80..82 -> "🌦" to "ливень"
        in 95..99 -> "⛈" to "гроза"
        else -> "☁" to "облачно"
    }

    fun refresh(ctx: Context): Now? {
        if (last != null && System.currentTimeMillis() - at < 30 * 60_000) return last
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=55.75&longitude=37.62" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=auto&wind_speed_unit=ms"
            val o = JSONObject(Net.text(url))
            val cur = o.getJSONObject("current")
            val (icon, sky) = describe(cur.getInt("weather_code"))
            val d = o.getJSONObject("daily")
            val names = listOf("сегодня", "завтра", "послезавтра")
            val days = (0 until d.getJSONArray("time").length()).map { i ->
                Day(names.getOrElse(i) { "" }, describe(d.getJSONArray("weather_code").getInt(i)).first,
                    Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt())
            }
            Now(Math.round(cur.getDouble("temperature_2m")).toInt(), Math.round(cur.getDouble("apparent_temperature")).toInt(),
                Math.round(cur.getDouble("wind_speed_10m")).toInt(), icon, sky, days).also { last = it; at = System.currentTimeMillis() }
        } catch (e: Exception) {
            last
        }
    }

    fun t(v: Int) = if (v > 0) "+$v°" else "$v°"
}
