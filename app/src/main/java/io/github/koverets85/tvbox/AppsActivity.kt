package io.github.koverets85.tvbox

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Batch uninstall / disable / autostart control (needs root). */
class AppsActivity : Activity() {
    private data class App(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val enabled: Boolean,
        val bootReceivers: List<String>,
        val autostartOn: Boolean,
        val lastUsed: Long,
        val traffic: Long,
    )

    companion object {
        val BOOT_ACTIONS = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
        /** Never touch: the box would break or we would lose ourselves. */
        val PROTECTED = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.packageinstaller", "com.android.permissioncontroller", "com.android.shell",
            "com.android.networkstack", "com.android.networkstack.permissionconfig", "com.android.providers.settings",
            "com.android.providers.media", "com.android.providers.media.module", "com.android.providers.downloads",
            "com.android.providers.tv", "com.android.externalstorage", "com.android.inputdevices",
            "com.android.keychain", "com.android.certinstaller", "com.android.wifi.resources",
            "com.android.bluetooth", "com.android.se", "com.android.vpndialogs", "com.android.documentsui",
            "com.android.inputmethod.latin", "org.liskovsoft.androidtv.rukeyboard",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.webview",
            "com.google.android.ext.services", "com.google.android.ext.shared", "android.ext.services",
            "com.google.android.modulemetadata", "com.google.android.tvlauncher", "com.google.android.tv.remote.service",
            "com.droidlogic", "com.droidlogic.wifion", "com.slimbox.tv.settings", "slim.box.tv.project",
            "com.topjohnwu.magisk", "io.github.koverets85.tvbox",
        )
    }

    private val apps = mutableListOf<App>()
    private val selected = mutableSetOf<String>()
    private var showSystem = false
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var modeBtn: Button
    private val df = SimpleDateFormat("dd.MM.yy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Ui.button(this, label, pad, onClick).also {
            buttons.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        modeBtn = button("Показать системные") { showSystem = !showSystem; selected.clear(); reload() }
        button("Удалить") { uninstallSelected() }
        button("Выкл / Вкл") { toggleEnabled() }
        button("Автозапуск") { toggleAutostart() }
        button("Вернуть всё") { undoAll() }
        status = TextView(this).apply { textSize = 15f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, pad / 3, 0, pad / 3) }
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@AppsActivity); adapter = Adapter() }
        root.addView(buttons)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        reload()
    }

    private fun reload() {
        modeBtn.text = if (showSystem) "Показать свои" else "Показать системные"
        status.text = "Загружаю список…"
        Thread {
            val l = collect(this, showSystem)
            runOnUiThread {
                apps.clear(); apps.addAll(l)
                list.adapter?.notifyDataSetChanged()
                status.text = "Программ: ${l.size}. Нажмите на строку, чтобы отметить, затем кнопку сверху. " +
                    "Сверху — те, что дольше всего не запускались."
            }
        }.start()
    }

    private fun collect(ctx: Context, system: Boolean): List<App> {
        val pm = ctx.packageManager
        val boot = HashMap<String, MutableList<String>>()
        for (a in BOOT_ACTIONS) {
            pm.queryBroadcastReceivers(Intent(a), PackageManager.MATCH_DISABLED_COMPONENTS).forEach {
                boot.getOrPut(it.activityInfo.packageName) { mutableListOf() }.let { l ->
                    if (it.activityInfo.name !in l) l.add(it.activityInfo.name)
                }
            }
        }
        val now = System.currentTimeMillis()
        val month = 30L * 86400000
        val usage = HashMap<String, Long>()
        val traffic = HashMap<Int, Long>()
        if (Diagnostics.hasUsageAccess(ctx)) {
            try {
                (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                    .queryAndAggregateUsageStats(now - month, now).forEach { (p, s) -> usage[p] = s.lastTimeUsed }
            } catch (e: Exception) { }
            try {
                val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
                @Suppress("DEPRECATION")
                for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
                    try {
                        val st = nsm.querySummary(type, null, now - month, now)
                        val b = NetworkStats.Bucket()
                        while (st.hasNextBucket()) { st.getNextBucket(b); traffic[b.uid] = (traffic[b.uid] ?: 0) + b.rxBytes + b.txBytes }
                        st.close()
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { ((it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) == system }
            .filter { it.packageName != ctx.packageName }
            .map { ai ->
                val recv = boot[ai.packageName] ?: emptyList()
                val on = recv.any {
                    pm.getComponentEnabledSetting(ComponentName(ai.packageName, it)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                App(ai.packageName, try { ai.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName },
                    system, ai.enabled, recv, on, usage[ai.packageName] ?: 0, traffic[ai.uid] ?: 0)
            }
            .sortedWith(compareBy({ it.lastUsed }, { it.label.lowercase() }))
    }

    private fun chosen(): List<App> {
        val c = apps.filter { it.pkg in selected }
        if (c.isEmpty()) Toast.makeText(this, "Сначала отметьте программы (нажать на строку)", Toast.LENGTH_SHORT).show()
        return c
    }

    private fun confirm(title: String, items: List<App>, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(items.joinToString("\n") { it.label })
            .setPositiveButton("Да") { _, _ -> action() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Runs root commands in background, then records undo entries and reloads. */
    private fun runRoot(what: String, cmds: List<String>, undo: List<String>) {
        status.text = "$what…"
        Thread {
            val ok = Root.check()
            val out = if (ok) Root.run(cmds.joinToString("; "), 120) else null
            runOnUiThread {
                if (!ok) {
                    status.text = "Нужен root: разрешите TVHub в Magisk и повторите"
                } else {
                    Undo.add(this, undo)
                    status.text = "$what — готово"
                    if (out != null && out.contains("Failure", true))
                        Toast.makeText(this, out.take(300), Toast.LENGTH_LONG).show()
                    selected.clear()
                    reload()
                }
            }
        }.start()
    }

    private fun uninstallSelected() {
        val c = chosen().ifEmpty { return }
        confirm("Удалить ${c.size} шт.?", c) {
            val cmds = c.map { if (it.system) "pm uninstall -k --user 0 ${it.pkg}" else "pm uninstall ${it.pkg}" }
            // system apps can be restored; user apps are gone (reinstall from the catalog)
            runRoot("Удаляю", cmds, c.filter { it.system }.map { "cmd package install-existing ${it.pkg}" })
        }
    }

    private fun toggleEnabled() {
        val c = chosen().ifEmpty { return }
        val toOff = c.filter { it.enabled }
        val toOn = c.filter { !it.enabled }
        confirm("Выключить ${toOff.size}, включить ${toOn.size}?", c) {
            runRoot("Переключаю",
                toOff.map { "pm disable-user --user 0 ${it.pkg}" } + toOn.map { "pm enable ${it.pkg}" },
                toOff.map { "pm enable ${it.pkg}" })
        }
    }

    private fun toggleAutostart() {
        val c = chosen().ifEmpty { return }
        val noBoot = c.filter { it.bootReceivers.isEmpty() }
        val toOff = c.filter { it.bootReceivers.isNotEmpty() && it.autostartOn }
        val toOn = c.filter { it.bootReceivers.isNotEmpty() && !it.autostartOn }
        val cmds = mutableListOf<String>()
        val undo = mutableListOf<String>()
        for (a in toOff) {
            a.bootReceivers.forEach { cmds += "pm disable '${a.pkg}/$it'"; undo += "pm enable '${a.pkg}/$it'" }
            cmds += "cmd appops set ${a.pkg} RUN_ANY_IN_BACKGROUND ignore"
            undo += "cmd appops set ${a.pkg} RUN_ANY_IN_BACKGROUND allow"
        }
        for (a in toOn) {
            a.bootReceivers.forEach { cmds += "pm enable '${a.pkg}/$it'" }
            cmds += "cmd appops set ${a.pkg} RUN_ANY_IN_BACKGROUND allow"
        }
        if (cmds.isEmpty()) {
            Toast.makeText(this, "У отмеченных нет автозапуска", Toast.LENGTH_SHORT).show(); return
        }
        confirm("Автозапуск: выключить ${toOff.size}, включить ${toOn.size}" +
            (if (noBoot.isNotEmpty()) " (без автозапуска: ${noBoot.size})" else ""), toOff + toOn) {
            runRoot("Меняю автозапуск", cmds, undo)
        }
    }

    private fun undoAll() {
        val u = Undo.all(this)
        if (u.isEmpty()) { Toast.makeText(this, "Нечего возвращать", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Вернуть всё, что выключали и удаляли из системных? (${u.size} действий)")
            .setMessage("Удалённые свои программы так не вернуть — их ставить из каталога.")
            .setPositiveButton("Вернуть") { _, _ ->
                status.text = "Возвращаю…"
                Thread {
                    val ok = Root.check()
                    if (ok) Root.run(u.joinToString("; "), 180)
                    runOnUiThread {
                        if (ok) { Undo.clear(this); status.text = "Возвращено"; reload() }
                        else status.text = "Нужен root"
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val desc: TextView = v.findViewById(R.id.desc)
        val action: TextView = v.findViewById(R.id.action)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val a = apps[position]
            val locked = a.pkg in PROTECTED
            h.name.text = a.label
            val tags = mutableListOf<String>()
            if (!a.enabled) tags += "ВЫКЛЮЧЕНА"
            if (a.bootReceivers.isNotEmpty()) tags += if (a.autostartOn) "автозапуск" else "автозапуск выкл"
            tags += if (a.lastUsed > 0) "запуск ${df.format(Date(a.lastUsed))}" else "не запускалась 30 дн"
            if (a.traffic > 0) tags += "трафик ${a.traffic / 1048576} МБ"
            if (locked) tags += "системная, не трогаем"
            h.desc.text = tags.joinToString(" · ") + "\n" + a.pkg
            h.desc.visibility = View.VISIBLE
            h.action.text = when {
                locked -> "🔒"
                a.pkg in selected -> "☑"
                else -> "☐"
            }
            h.action.textSize = 24f
            h.itemView.setOnClickListener {
                if (locked) return@setOnClickListener
                if (!selected.add(a.pkg)) selected.remove(a.pkg)
                notifyItemChanged(h.bindingAdapterPosition)
            }
        }
    }
}

/** Remembers how to revert our changes. */
object Undo {
    private const val KEY = "undo"
    private fun prefs(c: Context) = c.getSharedPreferences("undo", Context.MODE_PRIVATE)
    fun all(c: Context): List<String> = prefs(c).getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }
    fun add(c: Context, cmds: List<String>) {
        if (cmds.isEmpty()) return
        prefs(c).edit().putString(KEY, (all(c) + cmds).distinct().joinToString("\n")).apply()
    }
    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()
}
