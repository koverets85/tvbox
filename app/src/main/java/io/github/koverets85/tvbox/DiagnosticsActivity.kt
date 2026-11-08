package io.github.koverets85.tvbox

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DiagnosticsActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var status: TextView
    private var report = ""
    private lateinit var scroll: ScrollView

    /** Remote: left/right move between buttons, up/down scroll the report. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val step = (scroll.height * 0.8).toInt()
                scroll.smoothScrollBy(0, if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) -step else step)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
            buttons.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        val send = button("Отправить отчёт Claude") { send() }
        button("Доступ к статистике") { openUsageAccess() }
        button("Обновить") { refresh() }
        status = TextView(this).apply { textSize = 15f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, pad / 3, 0, pad / 3) }
        text = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(0xFFFFFFFF.toInt()) }
        scroll = ScrollView(this).apply {
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(text)
        }
        root.addView(buttons)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        send.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = "Собираю сведения…"
        Thread {
            val r = try { Diagnostics.build(this) } catch (e: Exception) { "Ошибка сбора: $e" }
            runOnUiThread {
                report = r
                text.text = r
                status.text = "◀ ▶ — кнопки,  ▲ ▼ — листать отчёт.  " + (if (Diagnostics.hasUsageAccess(this)) "Доступ к статистике: есть" else
                    "Доступ к статистике: НЕТ — нажмите «Доступ к статистике» и включите для ${getString(R.string.app_name)}")
            }
        }.start()
    }

    private fun send() {
        if (report.isEmpty()) return
        status.text = "Отправляю…"
        val r = report
        Thread {
            val msg = try {
                Report.send("ОТЧЁТ TVHUB\n" + r)
                "Отправлено. Напишите Claude в Telegram: «отчёт отправил»"
            } catch (e: Exception) { "Не отправилось: ${e.message}" }
            runOnUiThread { status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "На этой приставке нет такого экрана — включим через отладку (v2)", Toast.LENGTH_LONG).show()
        }
    }
}
