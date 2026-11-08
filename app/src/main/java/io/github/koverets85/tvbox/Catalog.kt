package io.github.koverets85.tvbox

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val REPO = "koverets85/tvbox"
const val CATALOG_URL = "https://raw.githubusercontent.com/$REPO/main/catalog.json"
const val SELF_VERSION_URL = "https://github.com/$REPO/releases/latest/download/version.json"
const val SELF_APK_URL = "https://github.com/$REPO/releases/latest/download/tvbox.apk"

data class CatalogApp(
    val pkg: String,
    val name: String,
    val description: String,
    val versionName: String,
    val apk: String,
)

data class SelfVersion(val versionCode: Long, val versionName: String)

object Net {
    fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "tvbox-app")
        return c
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

object Catalog {
    fun load(): List<CatalogApp> {
        val root = JSONObject(Net.text(CATALOG_URL + "?t=" + System.currentTimeMillis() / 60000))
        val arr = root.getJSONArray("apps")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CatalogApp(
                pkg = o.getString("package"),
                name = o.getString("name"),
                description = o.optString("description"),
                versionName = o.optString("versionName"),
                apk = o.getString("apk"),
            )
        }
    }

    fun selfVersion(): SelfVersion? = try {
        val o = JSONObject(Net.text(SELF_VERSION_URL))
        SelfVersion(o.getLong("versionCode"), o.getString("versionName"))
    } catch (e: Exception) {
        null
    }

    /** >0 if a is newer than b; compares numeric parts like 32.71 vs 32.7 */
    fun compareVersions(a: String, b: String): Int {
        val pa = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
        val pb = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
