package com.eddiew.commandersim.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val UA = "CommanderSim-Android"

/** Opens a GET connection, following redirects (GitHub sends downloads to its file CDN). */
private fun open(url: String, accept: String? = null, readTimeout: Int = 30_000): HttpURLConnection {
    var u = URL(url)
    repeat(6) {
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        if (code in 300..399) {
            u = URL(u, c.getHeaderField("Location"))
            c.disconnect()
        } else {
            return c
        }
    }
    error("Too many redirects")
}

/** GET as text. Returns null for 404; throws for other errors. Blocking. */
fun httpGet(url: String, accept: String? = null): String? {
    val c = open(url, accept)
    try {
        if (c.responseCode == 404) return null
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/** Downloads [url] to [out], reporting 0..1 progress. Blocking. */
fun httpDownload(url: String, out: File, expectedSize: Long = 0, onProgress: (Float) -> Unit = {}) {
    val c = open(url, readTimeout = 60_000)
    try {
        if (c.responseCode !in 200..299) error("Download failed (HTTP ${c.responseCode})")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
        out.parentFile?.mkdirs()
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    } finally {
        c.disconnect()
    }
}
