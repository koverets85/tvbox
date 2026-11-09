package io.github.koverets85.tvbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Black box": a root shell script that keeps writing system logs, kernel log and vitals
 * (temperature, load, free memory, top processes) to /data/tvhub. On each start the previous
 * session's files become prev-*, so after a hang + reboot the report shows what happened before it.
 */
object BlackBox {
    const val DIR = "/data/tvhub"

    private val SCRIPT = """
        mkdir -p $DIR; cd $DIR || exit 1
        if [ -f run.pid ] && grep -q bb.sh "/proc/${'$'}(cat run.pid)/cmdline" 2>/dev/null; then exit 0; fi
        rm -f prev-*
        for f in logcat.txt logcat.txt.1 logcat.txt.2 logcat.txt.3 kmsg.txt vitals.txt; do [ -f "${'$'}f" ] && mv -f "${'$'}f" "prev-${'$'}f"; done
        echo ${'$'}${'$'} > run.pid
        date '+%d.%m.%Y %H:%M:%S старт' > started.txt
        logcat -v time -b main,system,crash -f $DIR/logcat.txt -r 1024 -n 3 &
        (cat /proc/kmsg | grep -v 'avc:' > $DIR/kmsg.txt) &
        while true; do
          echo "== ${'$'}(date '+%d.%m %H:%M:%S') t=${'$'}(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null) load=${'$'}(cut -d' ' -f1-3 /proc/loadavg) avail=${'$'}(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2)kB"
          top -b -n 1 -m 5 2>/dev/null | tail -n 6
          sleep 30
        done > $DIR/vitals.txt
    """.trimIndent()

    /** Starts the recorder if it is not running yet. Blocking (runs su) — call from a background thread. */
    fun ensure(ctx: Context) {
        if (!Root.check()) return
        val b64 = android.util.Base64.encodeToString(SCRIPT.toByteArray(), android.util.Base64.NO_WRAP)
        Root.run("mkdir -p $DIR && echo $b64 | base64 -d > $DIR/bb.sh && (nohup sh $DIR/bb.sh >/dev/null 2>&1 &)", 20)
    }

    fun startAsync(ctx: Context) = Thread { try { ensure(ctx) } catch (e: Exception) { } }.start()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { BlackBox.ensure(ctx.applicationContext) } catch (e: Exception) { }
            pending.finish()
        }.start()
    }
}
