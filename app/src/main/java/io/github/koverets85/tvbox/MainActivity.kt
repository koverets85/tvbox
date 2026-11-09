package io.github.koverets85.tvbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {
    companion object {
        const val ACTION_REFRESH = "io.github.koverets85.tvbox.REFRESH"
    }

    /** One row on screen. */
    private data class Row(
        val title: String,
        val desc: String,
        val action: String,
        val onClick: () -> Unit,
    )

    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private val rows = mutableListOf<Row>()
    private var catalog: List<CatalogApp> = emptyList()
    private var selfVersion: SelfVersion? = null
    private var busy = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.title).text = "${getString(R.string.app_name)}  ·  ${myVersionName()}"
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        val filter = IntentFilter(ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(refreshReceiver, filter)
        load()
        BlackBox.startAsync(this)
    }

    override fun onDestroy() {
        unregisterReceiver(refreshReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (catalog.isNotEmpty()) rebuild()
    }

    private fun load() {
        status.text = "Загружаю каталог…"
        Thread {
            try {
                val c = Catalog.load()
                val s = Catalog.selfVersion()
                runOnUiThread {
                    catalog = c
                    selfVersion = s
                    status.text = "Программ в каталоге: ${c.size}"
                    rebuild()
                    list.post { list.getChildAt(0)?.requestFocus() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Нет связи с каталогом (${e.message}). Нажмите, чтобы повторить."
                    rows.clear()
                    rows.add(Row("Повторить", "Проверьте интернет", "↻") { load() })
                    list.adapter?.notifyDataSetChanged()
                    list.post { list.getChildAt(0)?.requestFocus() }
                }
            }
        }.start()
    }

    private fun rebuild() {
        rows.clear()
        val sv = selfVersion
        val appName = getString(R.string.app_name)
        if (sv != null && sv.versionCode > myVersionCode()) {
            rows.add(Row("Обновить $appName", "Есть новая версия ${sv.versionName} (у вас ${myVersionName()})", "Обновить") {
                downloadAndInstall(appName, SELF_APK_URL)
            })
        } else {
            rows.add(Row("Обновление $appName",
                if (sv == null) "Не удалось проверить — нажмите, чтобы проверить ещё раз"
                else "У вас последняя версия ${myVersionName()}", "Проверить") { load() })
        }
        rows.add(Row("Главный экран TVHub (проба)", "Свой главный экран: в нём «⚙ Вид» — темы, плитки, часы, погода", "Открыть") {
            startActivity(Intent(this, LauncherActivity::class.java))
        })
        rows.add(Row("Программы", "Удалить пачкой, выключить, убрать автозапуск (нужен root)", "Открыть") {
            startActivity(Intent(this, AppsActivity::class.java))
        })
        rows.add(Row("Очистить мусор", "Кэш всех программ и временные файлы", "Очистить") { cleanJunk() })
        rows.add(Row("Диагностика", "Что стоит на приставке, что запускается само и тратит интернет", "Открыть") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })
        for (app in catalog) {
            val installed = installedVersion(app.pkg)
            val (desc, action) = when {
                installed == null -> app.description to "Установить"
                Catalog.compareVersions(app.versionName, installed) > 0 ->
                    "Есть новая версия ${app.versionName} (у вас $installed)" to "Обновить"
                else -> app.description to "Открыть"
            }
            rows.add(Row(app.name, desc, action) {
                if (action == "Открыть") launch(app.pkg) else downloadAndInstall(app.name, app.apk)
            })
        }
        list.adapter?.notifyDataSetChanged()
    }

    private fun downloadAndInstall(name: String, url: String) {
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разрешите установку программ для ${getString(R.string.app_name)} и вернитесь", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        busy = true
        status.text = "Скачиваю $name…"
        Thread {
            try {
                val file = Installer.download(this, url) { p ->
                    runOnUiThread { status.text = if (p >= 0) "Скачиваю $name… $p%" else "Скачиваю $name…" }
                }
                runOnUiThread { status.text = "Устанавливаю $name…" }
                Installer.install(this, file)
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка: ${e.message}" }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun cleanJunk() {
        if (busy) return
        busy = true
        status.text = "Чищу…"
        Thread {
            fun free() = android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
            val before = free()
            val ok = Root.check()
            if (ok) Root.run("pm trim-caches 999G; rm -rf /data/local/tmp/* /sdcard/Android/data/*/cache/* 2>/dev/null", 180)
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            val freed = (free() - before).coerceAtLeast(0) / 1048576
            runOnUiThread {
                busy = false
                status.text = if (ok) "Очищено: $freed МБ" else "Нужен root: разрешите TVHub в Magisk и повторите"
            }
        }.start()
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
    }

    private fun installedVersion(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "0"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun myVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun myVersionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val desc: TextView = v.findViewById(R.id.desc)
        val action: TextView = v.findViewById(R.id.action)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rows[position]
            h.name.text = r.title
            h.desc.text = r.desc
            h.desc.visibility = if (r.desc.isEmpty()) View.GONE else View.VISIBLE
            h.action.text = r.action
            h.itemView.setOnClickListener { r.onClick() }
        }
    }
}
