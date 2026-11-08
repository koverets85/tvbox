package io.github.koverets85.tvbox

import android.content.Context
import android.graphics.Color

/** One look setting: key, title and (id, label) values. The first value is the default. */
data class Opt(val key: String, val label: String, val values: List<Pair<String, String>>)

data class Theme(
    val bg: Int, val surface: Int, val fg: Int, val accent: Int, val accentFg: Int,
    val sat: Float, val lig: Float, val typeface: String,
)

object Look {
    val OPTIONS = listOf(
        Opt("layout", "Раскладка", listOf("rows" to "Ряды + баннер", "grid" to "Сетка", "shelf" to "Полка", "mini" to "Минимализм")),
        Opt("theme", "Тема", listOf("night" to "Ночь", "graphite" to "Графит", "cinema" to "Кинозал", "soft" to "Мягкая", "neon" to "Неон", "light" to "Светлая")),
        Opt("wall", "Обои", listOf("gradient" to "Градиент", "plain" to "Однотонные", "aurora" to "Сияние", "app" to "Цвет программы")),
        Opt("tile", "Вид плитки", listOf("banner" to "Баннер", "card" to "Значок-карточка", "round" to "Круглый значок")),
        Opt("size", "Размер плиток", listOf("m" to "Средние", "s" to "Мелкие", "l" to "Крупные")),
        Opt("radius", "Углы", listOf("10" to "Чуть скруглённые", "0" to "Прямые", "22" to "Сильно скруглённые")),
        Opt("label", "Подпись", listOf("under" to "Под плиткой", "on" to "На плитке", "off" to "Без подписи")),
        Opt("focus", "Выделение пультом", listOf("zoom" to "Увеличение", "lift" to "Подъём", "glow" to "Свечение", "frame" to "Рамка")),
        Opt("section", "Оформление рядов", listOf("plain" to "Без фона", "panel" to "Подложка", "border" to "Рамка")),
        Opt("titles", "Заголовки рядов", listOf("on" to "Показывать", "off" to "Скрыть")),
        Opt("hero", "Баннер выбранной программы", listOf("on" to "Есть", "off" to "Нет")),
        Opt("topbar", "Верхняя строка", listOf("full" to "Во всю ширину", "float" to "Плавающая", "off" to "Скрыта")),
        Opt("clock", "Часы", listOf("small" to "В строке", "big" to "Крупные", "off" to "Без часов")),
        Opt("weather", "Погода", listOf("short" to "Кратко", "detail" to "Подробно", "days" to "На 3 дня", "off" to "Нет")),
        Opt("sys", "Состояние приставки", listOf("off" to "Нет", "on" to "Память, Wi-Fi, место")),
    )

    val THEMES = mapOf(
        "night" to Theme(c("#0b1020"), c("#18213d"), c("#eef2ff"), c("#5b7cff"), c("#ffffff"), .62f, .46f, "sans-serif"),
        "graphite" to Theme(c("#16181c"), c("#24272d"), c("#f1f1f1"), c("#e8e8e8"), c("#111111"), .18f, .38f, "sans-serif-medium"),
        "cinema" to Theme(c("#140c0a"), c("#2a1914"), c("#f6e9dc"), c("#e0a14a"), c("#1a0f08"), .45f, .34f, "serif"),
        "soft" to Theme(c("#20243a"), c("#2e3452"), c("#f4f2ff"), c("#f2a7c3"), c("#2a1730"), .55f, .62f, "sans-serif-light"),
        "neon" to Theme(c("#05040c"), c("#130f2a"), c("#f0eaff"), c("#00e5ff"), c("#020208"), .90f, .48f, "sans-serif-condensed"),
        "light" to Theme(c("#eef1f6"), c("#ffffff"), c("#1b1f2a"), c("#2f5bff"), c("#ffffff"), .70f, .52f, "sans-serif"),
    )

    private fun c(s: String) = Color.parseColor(s)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("look", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String {
        val opt = OPTIONS.first { it.key == key }
        val v = prefs(ctx).getString(key, null)
        return if (v != null && opt.values.any { it.first == v }) v else opt.values.first().first
    }

    fun set(ctx: Context, key: String, value: String) = prefs(ctx).edit().putString(key, value).apply()

    fun cycle(ctx: Context, key: String, dir: Int) {
        val opt = OPTIONS.first { it.key == key }
        val i = opt.values.indexOfFirst { it.first == get(ctx, key) }
        val n = opt.values.size
        set(ctx, key, opt.values[((i + dir) % n + n) % n].first)
    }

    fun labelOf(ctx: Context, key: String): String {
        val opt = OPTIONS.first { it.key == key }
        val v = get(ctx, key)
        return opt.values.first { it.first == v }.second
    }

    fun theme(ctx: Context) = THEMES[get(ctx, "theme")]!!

    /** HSL (h 0..360, s,l 0..1) to ARGB. */
    fun hsl(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val hh = ((h % 360) + 360) % 360 / 60f
        val x = c * (1 - Math.abs(hh % 2 - 1))
        val (r, g, b) = when {
            hh < 1 -> Triple(c, x, 0f); hh < 2 -> Triple(x, c, 0f); hh < 3 -> Triple(0f, c, x)
            hh < 4 -> Triple(0f, x, c); hh < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        return Color.argb(alpha, ((r + m) * 255).toInt().coerceIn(0, 255), ((g + m) * 255).toInt().coerceIn(0, 255), ((b + m) * 255).toInt().coerceIn(0, 255))
    }

    fun withAlpha(color: Int, a: Int) = (color and 0x00ffffff) or (a shl 24)
}
