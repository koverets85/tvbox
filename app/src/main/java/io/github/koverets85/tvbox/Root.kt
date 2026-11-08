package io.github.koverets85.tvbox

import java.util.concurrent.TimeUnit

/** Runs shell commands as root via Magisk `su`. First call shows Magisk's "Grant" prompt on the TV. */
object Root {
    @Volatile var available: Boolean? = null
        private set

    fun run(cmd: String, timeoutSec: Long = 30): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().use { r -> r.forEachLine { out.append(it).append('\n') } } }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroy()
        reader.join(2000)
        out.toString()
    } catch (e: Exception) {
        null
    }

    fun check(): Boolean {
        val ok = run("id", 20)?.contains("uid=0") == true
        available = ok
        return ok
    }
}
