package io.github.koverets85.tvbox

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val intent: Intent?,
    val hue: Float,
    val special: String? = null, // "look" = open look panel
)

object Apps {
    private val FAV = listOf("com.vk.tv", "com.liskovsoft.smarttubetv", "org.smarttube", "ru.more.play", "gpm.tnt_premier",
        "com.google.android.youtube.tv", "ru.kinopoisk", "ru.ivi", "tv.wink", "com.start.tv")
    private val KINO = listOf("lampa", "mobi.zona", "ru.yourok.num", "kinotrend", "ru.rutube", "lazymedia", "club.episod",
        "viewbox", "deeplex", "kino", "cinema", "movie", "hdrezka", "filmix")
    private val TV = listOf("smotrim", "ottnavigator", "vintera", "iptv", "limehd", "ctvshka", "peers", "tv.ru", "multfilm", "channel")
    private val TOOLS = listOf("org.videolan.vlc", "net.gtvbox", "torrserve", "filemanager", "explorer", "io.github.koverets85.tvbox",
        "com.android.tv.settings", "speedtest", "analiti", "downloader", "magisk", "aptoide", "technozon")

    val CATS = listOf("fav" to "Избранное", "kino" to "Кино и сериалы", "tv" to "ТВ-каналы", "tools" to "Инструменты", "other" to "Остальное")

    fun category(pkg: String): String = when {
        FAV.any { pkg.startsWith(it) } -> "fav"
        TOOLS.any { pkg.contains(it) } -> "tools"
        TV.any { pkg.contains(it) } -> "tv"
        KINO.any { pkg.contains(it) } -> "kino"
        else -> "other"
    }

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        for (q in intents) {
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName in seen) continue
                if (ai.name.endsWith(".LauncherActivity") && ai.packageName == ctx.packageName) continue
                val launch = Intent(q).setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val label = ri.loadLabel(pm).toString()
                val banner = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (e: Exception) { null }
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                seen[ai.packageName] = AppEntry(ai.packageName, label, banner, icon, launch, (ai.packageName.hashCode() and 0xffff) % 360f)
            }
        }
        if ("com.android.tv.settings" !in seen) {
            seen["settings"] = AppEntry("settings", "Настройки", null, null,
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 220f)
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun lookTile() = AppEntry("look", "Вид экрана", null, null, null, 260f, special = "look")
}
