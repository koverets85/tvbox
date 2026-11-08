package io.github.koverets85.tvbox

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Our home screen. Every look option is applied live; the «Вид экрана» panel changes them with the remote. */
class LauncherActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var panel: LinearLayout
    private var apps: List<AppEntry> = emptyList()
    private var focused: AppEntry? = null
    private val clocks = mutableListOf<Pair<TextView, String>>() // view + format
    private val handler = Handler(Looper.getMainLooper())
    private var heroTitle: TextView? = null
    private var heroArt: View? = null
    private var heroImage: ImageView? = null
    private var lastFocusPkg: String? = null

    private val dpF get() = resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * dpF).toInt()
    private fun opt(k: String) = Look.get(this, k)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        content = FrameLayout(this)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(dp(400), MATCH_PARENT, Gravity.END))
        setContentView(root)
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        reloadApps()
        tick()
        Thread { Weather.refresh(this); runOnUiThread { if (opt("weather") != "off") render() } }.start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun reloadApps() {
        apps = Apps.load(this)
        render()
    }

    private fun tick() {
        handler.removeCallbacksAndMessages(null)
        val now = Date()
        for ((tv, fmt) in clocks) tv.text = SimpleDateFormat(fmt, Locale("ru")).format(now)
        handler.postDelayed({ tick() }, 20_000)
    }

    // ---------- keys ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { togglePanel(); return true }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) togglePanel()
        // as a home screen, Back does nothing else
    }

    // ---------- rendering ----------
    private fun render() {
        val T = Look.theme(this)
        clocks.clear(); heroTitle = null; heroArt = null; heroImage = null
        firstTileMade = false
        content.removeAllViews()
        root.background = wallpaper(T, focused?.hue ?: 220f)

        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(22), dp(40), dp(16))
        }
        content.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val byCat = Apps.CATS.map { (k, t) -> Triple(k, t, apps.filter { Apps.category(it.pkg) == k }) }.filter { it.third.isNotEmpty() }
        val favs = byCat.firstOrNull { it.first == "fav" }?.third ?: apps.take(6)
        val all = apps + Apps.lookTile()
        val layout = opt("layout")

        if (layout != "shelf" && layout != "mini") topBar(T)?.let { layer.addView(it) }
        when (layout) {
            "rows" -> {
                if (opt("hero") == "on") layer.addView(hero(T, dp(150)), LinearLayout.LayoutParams(MATCH_PARENT, dp(150)).apply { topMargin = dp(10); bottomMargin = dp(6) })
                else widgetsRow(T)?.let { layer.addView(it) }
                val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                byCat.forEachIndexed { i, (k, t, list) ->
                    val items = if (k == "tools") list + Apps.lookTile() else list
                    col.addView(row(T, t, items))
                }
                if (byCat.none { it.first == "tools" }) col.addView(row(T, "Инструменты", listOf(Apps.lookTile())))
                scroll.addView(col)
                layer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            "grid" -> {
                widgetsRow(T)?.let { layer.addView(it) }
                if (opt("titles") == "on") layer.addView(text("Все программы", 22f, T.fg, bold = true, T = T).apply { setPadding(dp(4), dp(8), 0, dp(4)) })
                val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
                scroll.addView(grid(T, all))
                layer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            "shelf" -> {
                layer.setPadding(0, 0, 0, dp(16))
                layer.addView(hero(T, dp(250), shelf = true), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                layer.addView(row(T, if (opt("titles") == "on") "Все программы" else null, all).apply { setPadding(dp(40), 0, dp(40), 0) })
            }
            else -> { // mini
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(20), dp(8), 0) }
                val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                if (opt("clock") != "off") {
                    left.addView(clock(T, if (opt("clock") == "big") 84f else 48f, "HH:mm", bold = true))
                    left.addView(clock(T, 20f, "EEEE, d MMMM", bold = false))
                }
                weatherInline(T)?.let { left.addView(it.apply { setPadding(0, dp(10), 0, 0) }) }
                top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                widgetColumn(T, includeClock = false, includeInlineWeather = false)?.let { top.addView(it) }
                layer.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                layer.addView(row(T, null, favs + Apps.lookTile()))
            }
        }
        tick()
        // restore focus
        content.post {
            if (panel.visibility == View.VISIBLE) return@post
            val target = lastFocusPkg?.let { content.findViewWithTag<View>("tile:$it") } ?: content.findViewWithTag<View>("first")
            (target ?: findFirstFocusable(content))?.requestFocus()
        }
    }

    private fun findFirstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findFirstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun wallpaper(T: Theme, hue: Float): android.graphics.drawable.Drawable {
        val base = GradientDrawable().apply { setColor(T.bg) }
        fun radial(color: Int, cx: Float, cy: Float, r: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, Look.withAlpha(color, 0))
            setGradientCenter(cx, cy)
            gradientRadius = r * resources.displayMetrics.widthPixels
        }
        return when (opt("wall")) {
            "plain" -> base
            "aurora" -> LayerDrawable(arrayOf(base,
                radial(Look.withAlpha(T.accent, 0x77), .15f, .1f, .55f),
                radial(Look.withAlpha(Look.hsl(hueOf(T.accent) + 60, .8f, .55f), 0x66), .85f, .35f, .5f),
                radial(Look.withAlpha(Look.hsl(hueOf(T.accent) + 300, .7f, .45f), 0x66), .5f, 1.05f, .6f)))
            "app" -> LayerDrawable(arrayOf(base, radial(Look.hsl(hue, T.sat, minOf(T.lig, .45f), 0xbb), .75f, .2f, .8f)))
            else -> LayerDrawable(arrayOf(base,
                radial(Look.withAlpha(T.accent, 0x55), .9f, -.1f, .8f),
                radial(Look.withAlpha(T.accent, 0x33), -.1f, 1.1f, .6f)))
        }
    }

    private fun hueOf(color: Int): Float {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv); return hsv[0]
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, T: Theme? = null) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(T?.typeface ?: "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun clock(T: Theme, size: Float, fmt: String, bold: Boolean): TextView =
        text("", size, T.fg, bold, T).also { clocks += it to fmt }

    private fun rounded(color: Int, r: Float, strokeColor: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
        if (strokeColor != null) setStroke(strokeW, strokeColor)
    }

    // ---------- top bar & widgets ----------
    private fun topBar(T: Theme): View? {
        val mode = opt("topbar")
        if (mode == "off") return null
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        weatherInline(T)?.let { right.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(18) }) }
        if (opt("clock") == "small") right.addView(clock(T, 22f, "HH:mm", bold = true))
        val gear = text("  ⚙ Вид  ", 17f, T.fg, T = T).apply {
            isFocusable = true; setPadding(dp(10), dp(6), dp(10), dp(6))
            background = focusBg(T)
            setOnClickListener { togglePanel() }
        }
        right.addView(gear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
        if (mode == "full") {
            bar.addView(text("Главная", 19f, T.fg, bold = true, T = T), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            bar.addView(right)
            bar.background = rounded(Look.withAlpha(T.surface, 0xb0), dp(14).toFloat())
            bar.setPadding(dp(20), dp(8), dp(12), dp(8))
            return bar
        }
        // float: capsule on the right
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        right.background = rounded(Look.withAlpha(T.surface, 0xc0), dp(30).toFloat())
        right.setPadding(dp(20), dp(6), dp(8), dp(6))
        wrap.addView(right)
        return wrap
    }

    private fun focusBg(T: Theme) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(T.accent, dp(10).toFloat()))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(10).toFloat()))
    }

    private fun weatherInline(T: Theme): TextView? {
        val w = Weather.last ?: return null
        return when (opt("weather")) {
            "short" -> text("${w.icon} ${Weather.t(w.temp)}", 19f, T.fg, T = T)
            "detail" -> text("${w.icon} ${Weather.t(w.temp)} ${w.sky} · ощущается ${Weather.t(w.feels)} · ветер ${w.wind} м/с", 17f, T.fg, T = T)
            else -> null
        }
    }

    private fun card(T: Theme, child: View): View = FrameLayout(this).apply {
        background = rounded(Look.withAlpha(T.surface, 0xd0), dp(14).toFloat())
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(child)
    }

    private fun weatherDays(T: Theme): View? {
        val w = Weather.last ?: return null
        if (opt("weather") != "days") return null
        val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in w.days) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
            c.addView(text(d.name, 13f, Look.withAlpha(T.fg, 0xc0), T = T))
            c.addView(text(d.icon, 24f, T.fg, T = T))
            c.addView(text("${Weather.t(d.max)} / ${Weather.t(d.min)}", 14f, T.fg, bold = true, T = T))
            rowL.addView(c)
        }
        return card(T, rowL)
    }

    private fun sysWidget(T: Theme): View? {
        if (opt("sys") != "on") return null
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedGb = (mi.totalMem - mi.availMem) / 1e9
        val totalGb = mi.totalMem / 1e9
        @Suppress("DEPRECATION")
        val speed = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.linkSpeed } catch (e: Exception) { -1 }
        val st = StatFs(Environment.getDataDirectory().path)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(String.format(Locale("ru"), "Память  %.1f из %.1f ГБ", usedGb, totalGb), 15f, T.fg, T = T))
        col.addView(text(if (speed > 0) "Wi-Fi  $speed Мбит/с" else "Wi-Fi  нет", 15f, T.fg, T = T))
        col.addView(text(String.format(Locale("ru"), "Свободно  %.1f ГБ", st.availableBytes / 1e9), 15f, T.fg, T = T))
        return card(T, col)
    }

    /** Clock (big) + weather widgets + system info, stacked vertically. */
    private fun widgetColumn(T: Theme, includeClock: Boolean, includeInlineWeather: Boolean): View? {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (includeClock && opt("clock") == "big") {
            col.addView(clock(T, 56f, "HH:mm", bold = true))
            col.addView(clock(T, 16f, "EEEE, d MMMM", bold = false))
        }
        if (includeInlineWeather && opt("topbar") == "off") weatherInline(T)?.let { col.addView(it) }
        if (includeClock && opt("clock") == "small" && opt("topbar") == "off") col.addView(clock(T, 28f, "HH:mm", bold = true))
        weatherDays(T)?.let { col.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
        sysWidget(T)?.let { col.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
        return if (col.childCount == 0) null else col
    }

    private fun widgetsRow(T: Theme): View? {
        val w = widgetColumn(T, includeClock = true, includeInlineWeather = true) ?: return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, dp(4))
            addView(w)
        }
    }

    // ---------- hero ----------
    private fun hero(T: Theme, height: Int, shelf: Boolean = false): View {
        val box = FrameLayout(this)
        val r = if (shelf) 0f else (opt("radius").toFloat() + 4) * dpF
        val art = View(this)
        heroArt = art
        box.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = .95f }
        heroImage = img
        box.addView(img, FrameLayout.LayoutParams(dp(height * 1.2f / dpF), MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(if (shelf) 40 else 24) })
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(T.surface, Look.withAlpha(T.surface, 0xcc), Look.withAlpha(T.surface, 0)))
        }
        if (!shelf) box.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        else box.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(T.bg, Look.withAlpha(T.bg, 0)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (shelf) 40 else 24), 0, 0, dp(18)) }
        val title = text("", if (shelf) 40f else 32f, T.fg, bold = true, T = T)
        heroTitle = title
        txt.addView(title)
        txt.addView(text("ОК — открыть", 15f, Look.withAlpha(T.fg, 0xbb), T = T).apply { setPadding(0, dp(6), 0, 0) })
        box.addView(txt, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (shelf && opt("topbar") != "off") {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            weatherInline(T)?.let { line.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(16) }) }
            if (opt("clock") == "small") line.addView(clock(T, 24f, "HH:mm", bold = true))
            line.addView(text("  ⚙ Вид  ", 17f, T.fg, T = T).apply {
                isFocusable = true; setPadding(dp(10), dp(6), dp(10), dp(6)); background = focusBg(T); setOnClickListener { togglePanel() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            widgets.addView(line)
        }
        widgetColumn(T, includeClock = true, includeInlineWeather = !shelf)?.let { widgets.addView(it) }
        box.addView(widgets, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(if (shelf) 40 else 18), 0) })
        if (r > 0) { box.outlineProvider = ViewOutlineProvider.BACKGROUND; box.background = rounded(T.surface, r); box.clipToOutline = true }
        else box.setBackgroundColor(T.surface)
        updateHero(T)
        return box
    }

    private fun updateHero(T: Theme = Look.theme(this)) {
        val a = focused ?: apps.firstOrNull() ?: return
        heroTitle?.text = a.label
        heroArt?.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Look.hsl(a.hue, T.sat, T.lig), Look.hsl(a.hue + 28, T.sat, maxOf(.14f, T.lig - .16f))))
        heroImage?.setImageDrawable(a.banner ?: a.icon)
        if (opt("wall") == "app") root.background = wallpaper(T, a.hue)
    }

    // ---------- tiles ----------
    private fun tileSize(): Pair<Int, Int> {
        val base = when (opt("size")) { "s" -> 128; "l" -> 196; else -> 160 } * (if (opt("layout") == "mini" || opt("layout") == "shelf") 1.1f else 1f)
        return when (opt("tile")) {
            "card" -> dp(base * .62f) to dp(base * .62f)
            "round" -> dp(base * .56f) to dp(base * .56f)
            else -> dp(base) to dp(base * .5625f)
        }
    }

    private fun tile(T: Theme, a: AppEntry, first: Boolean): View {
        val (w, h) = tileSize()
        val type = opt("tile")
        val iconish = type != "banner"
        val labelMode = opt("label")
        val under = labelMode == "under" || (labelMode == "on" && iconish)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(10), dp(8), dp(4))
        }
        val t = FrameLayout(this)
        val r = if (type == "round") w / 2f else opt("radius").toFloat() * dpF
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Look.hsl(a.hue, T.sat, T.lig), Look.hsl(a.hue + 28, T.sat, maxOf(.14f, T.lig - .16f)))).apply {
            if (type == "round") shape = GradientDrawable.OVAL else cornerRadius = r
        }
        t.background = bg
        t.outlineProvider = ViewOutlineProvider.BACKGROUND
        t.clipToOutline = true
        when {
            a.special == "look" -> t.addView(text("⚙", h * .45f / dpF, Color.WHITE).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            !iconish && a.banner != null -> t.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(a.banner) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            a.icon != null -> {
                val s = (minOf(w, h) * (if (iconish) .62f else .5f)).toInt()
                t.addView(ImageView(this).apply { setImageDrawable(a.icon) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
            }
            else -> t.addView(text(a.label.take(2), h * .3f / dpF, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        if (labelMode == "on" && !iconish) {
            t.addView(text(a.label, 14f, Color.WHITE, bold = true).apply {
                setShadowLayer(4f, 0f, 1f, Color.BLACK); maxLines = 1; setPadding(dp(8), 0, dp(8), dp(6))
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        t.isFocusable = true
        t.isClickable = true
        t.tag = if (first) "first" else "tile:${a.pkg}"
        if (lastFocusPkg == a.pkg) t.tag = "tile:${a.pkg}"
        t.setOnClickListener { open(a) }
        val focusMode = opt("focus")
        t.setOnFocusChangeListener { v, has ->
            when (focusMode) {
                "zoom" -> { v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(150).start(); v.elevation = if (has) dp(12).toFloat() else 0f }
                "lift" -> { v.animate().translationY(if (has) -dp(8).toFloat() else 0f).setDuration(150).start(); v.elevation = if (has) dp(16).toFloat() else 0f }
                "glow" -> { v.foreground = if (has) rounded(Color.TRANSPARENT, r, T.accent, dp(4)) else null; v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(150).start() }
                else -> v.foreground = if (has) rounded(Color.TRANSPARENT, r, T.fg, dp(3)) else null
            }
            if (has) {
                focused = a
                if (a.special == null) lastFocusPkg = a.pkg
                updateHero(T)
            }
        }
        cell.addView(t, LinearLayout.LayoutParams(w, h))
        if (under) cell.addView(text(a.label, 14f, T.fg, T = T).apply {
            maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxWidth = (w * 1.3f).toInt()
        })
        return cell
    }

    private fun sectionStyle(T: Theme, v: LinearLayout) {
        val r = (opt("radius").toFloat() + 4) * dpF
        when (opt("section")) {
            "panel" -> { v.background = rounded(Look.withAlpha(T.surface, 0xb0), r); v.setPadding(dp(10), dp(8), dp(10), dp(2)) }
            "border" -> { v.background = rounded(Color.TRANSPARENT, r, Look.withAlpha(T.fg, 0x40), dp(2)); v.setPadding(dp(10), dp(8), dp(10), dp(2)) }
        }
    }

    private var firstTileMade = false

    private fun row(T: Theme, title: String?, items: List<AppEntry>): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        if (title != null && opt("titles") == "on") box.addView(text(title, 18f, T.fg, bold = true, T = T).apply { setPadding(dp(8), dp(4), 0, 0) })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(40), dp(8)) }
        items.forEach { line.addView(tile(T, it, !firstTileMade).also { firstTileMade = true }) }
        hs.addView(line)
        box.addView(hs)
        sectionStyle(T, box)
        box.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        return box
    }

    private fun grid(T: Theme, items: List<AppEntry>): View {
        val (w, _) = tileSize()
        val cellW = w + dp(16)
        val avail = resources.displayMetrics.widthPixels - dp(80)
        val cols = maxOf(2, avail / cellW)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(4), 0, dp(20)) }
        items.chunked(cols).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            chunk.forEach { r.addView(tile(T, it, !firstTileMade).also { firstTileMade = true }) }
            col.addView(r)
        }
        sectionStyle(T, col)
        return col
    }

    private fun open(a: AppEntry) {
        if (a.special == "look") { togglePanel(); return }
        try { startActivity(a.intent) } catch (e: Exception) { Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show() }
    }

    // ---------- look panel ----------
    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            firstTileMade = false
            render()
            return
        }
        buildPanel()
        panel.visibility = View.VISIBLE
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.findViewWithTag<View>("opt:0")?.requestFocus()
    }

    private fun buildPanel() {
        val T = Look.theme(this)
        panel.removeAllViews()
        panel.background = rounded(Look.withAlpha(Color.parseColor("#0d1018"), 0xf2), 0f)
        panel.setPadding(dp(18), dp(18), dp(18), dp(18))
        panel.addView(text("Вид экрана", 22f, Color.WHITE, bold = true).apply { setPadding(dp(6), 0, 0, dp(4)) })
        panel.addView(text("◀ ▶ — менять,  ▲ ▼ — выбрать,  Назад — сохранить", 13f, Color.parseColor("#b3ffffff")).apply { setPadding(dp(6), 0, 0, dp(10)) })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Look.OPTIONS.forEachIndexed { i, o ->
            val tv = TextView(this).apply {
                textSize = 16f; setTextColor(Color.WHITE)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isFocusable = true; isClickable = true
                tag = "opt:$i"
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(T.accent, dp(10).toFloat()))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(10).toFloat()))
                }
            }
            fun refresh() { tv.text = "${o.label}:   ◀ ${Look.labelOf(this, o.key)} ▶" }
            refresh()
            fun change(dir: Int) {
                Look.cycle(this, o.key, dir); refresh()
                firstTileMade = false
                render()
                if (o.key == "theme") buildPanel().also { panel.findViewWithTag<View>("opt:$i")?.requestFocus() }
            }
            tv.setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { change(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { change(1); true }
                    else -> false
                }
            }
            tv.setOnClickListener { change(1) }
            list.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(2) })
        }
        fun action(label: String, f: () -> Unit) = TextView(this).apply {
            text = label; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true; isClickable = true
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(T.accent, dp(10).toFloat()))
                addState(intArrayOf(), rounded(Look.withAlpha(Color.WHITE, 0x22), dp(10).toFloat()))
            }
            setOnClickListener { f() }
            list.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        action("✓ Готово") { togglePanel() }
        action("Сделать главным экраном приставки") { setAsHome(true) }
        action("Вернуть прежний главный экран") { setAsHome(false) }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun setAsHome(ours: Boolean) {
        Thread {
            val ok = Root.check()
            var msg = "Нужен root: разрешите TVHub в Magisk"
            if (ok) {
                val target = if (ours) "$packageName/.LauncherActivity" else {
                    val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                        .map { it.activityInfo }.filter { it.packageName != packageName && it.packageName != "com.android.tv.settings" }
                    val pick = homes.firstOrNull { it.packageName == "com.google.android.tvlauncher" } ?: homes.firstOrNull()
                    pick?.let { "${it.packageName}/${it.name}" }
                }
                msg = if (target == null) "Другой главный экран не найден" else {
                    val out = Root.run("cmd package set-home-activity $target") ?: ""
                    if (out.contains("Success", true) || out.isBlank()) (if (ours) "Готово: TVHub — главный экран. Проверьте кнопкой «Домой»" else "Вернул прежний главный экран")
                    else "Не получилось: ${out.take(200)}"
                }
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
