package com.eddiew.commandersim.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the repo's GitHub Releases for a newer APK, downloads it and opens Android's installer. */
class Updater(private val context: Context) {

    data class Release(val version: String, val name: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

    companion object {
        const val REPO = "eddie-w2/Commander-simulator"

        /** Numeric comparison of versions like "0.1.02" or "v0.1.10". Positive when [a] is newer. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        /** Reads the GitHub "latest release" JSON. */
        fun parseRelease(json: String): Release? {
            val o = AppJson.parseToJsonElement(json).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true } ?: return null
            return Release(
                version = tag.removePrefix("v"),
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: tag,
                notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
                apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                pageUrl = o["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    val currentVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The latest release if it is newer than this app, otherwise null. */
    suspend fun checkForUpdate(): Release? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CommanderSim-Android")
            if (conn.responseCode == 404) return@withContext null // no releases yet
            if (conn.responseCode !in 200..299) error("GitHub returned ${conn.responseCode}")
            val release = parseRelease(conn.inputStream.bufferedReader().use { it.readText() }) ?: return@withContext null
            if (compareVersions(release.version, currentVersion) > 0) release else null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app cache, reporting 0..1 progress. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "commander-sim-${release.version}.apk")
        httpDownload(release.apkUrl, out, release.apkSize, onProgress)
        out
    }

    /** True if Android lets this app open the package installer. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the downloaded APK to Android's installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
