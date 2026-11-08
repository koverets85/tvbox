package io.github.koverets85.tvbox

/** Uploads the diagnostics report to Claude's receiver (same one the "Cameras TV" app uses). */
object Report {
    private const val URL = "https://ozpost.ru/tvdiag-5c4e"

    fun send(text: String) {
        val body = text.toByteArray()
        val c = Net.open(URL)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
