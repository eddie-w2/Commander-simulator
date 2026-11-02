package com.eddiew.commandersim.sim.forge

import android.content.Context
import com.eddiew.commandersim.data.AppJson
import com.eddiew.commandersim.data.httpDownload
import com.eddiew.commandersim.data.httpGet
import com.eddiew.commandersim.sim.ENGINE_API_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Describes a Forge pack (engine + card data). Published as manifest.json next to forge-pack.apk. */
@Serializable
data class PackManifest(
    val forgeCommit: String,
    val forgeDate: String? = null,
    val apiVersion: Int,
    val kotlin: String,
    val sha256: String? = null,
    val size: Long = 0,
    val builtAt: String? = null,
) {
    val shortCommit: String get() = forgeCommit.take(8)
    val label: String get() = "Forge ${shortCommit}" + (forgeDate?.let { " · ${it.take(10)}" } ?: "")
}

/** A pack installed on the phone. */
data class InstalledPack(val id: String, val manifest: PackManifest, val apk: File, val bundled: Boolean)

/** A newer pack offered on GitHub. */
data class RemotePack(val manifest: PackManifest, val apkUrl: String)

/**
 * Manages Forge packs: the one built into the APK, and newer ones downloaded from the repo's `forge-pack` release.
 * A downloaded pack is only used if it was built for this app's engine API and Kotlin version.
 */
class ForgePacks(private val context: Context) {
    companion object {
        const val REPO = "eddie-w2/Commander-simulator"
        const val RELEASE_TAG = "forge-pack"
        private const val KEEP_DOWNLOADED = 2
    }

    private val root = File(context.filesDir, "forge-packs")
    private val selectedFile = File(root, "selected.txt")
    private val _active = MutableStateFlow<InstalledPack?>(null)
    /** The pack simulations use. */
    val active: StateFlow<InstalledPack?> = _active.asStateFlow()

    private fun compatible(m: PackManifest) = m.apiVersion == ENGINE_API_VERSION && m.kotlin == KotlinVersion.CURRENT.toString()

    /** The pack built into this APK, copied out of the assets once per app version. */
    @Synchronized
    fun bundled(): InstalledPack? {
        val props = runCatching {
            context.assets.open("forge/bundled.txt").bufferedReader().readLines()
                .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.getOrNull() ?: return null
        val manifest = PackManifest(
            forgeCommit = props["forgeCommit"] ?: "unknown",
            apiVersion = props["apiVersion"]?.toIntOrNull() ?: ENGINE_API_VERSION,
            kotlin = props["kotlin"] ?: KotlinVersion.CURRENT.toString(),
            size = props["size"]?.toLongOrNull() ?: 0,
        )
        val id = "bundled-${manifest.shortCommit}-${manifest.size}"
        val dir = File(root, id)
        val apk = File(dir, "forge-pack.apk")
        if (!apk.exists() || apk.length() != manifest.size) {
            root.listFiles()?.filter { it.name.startsWith("bundled-") && it.name != id }?.forEach { it.deleteRecursively() }
            dir.mkdirs()
            val tmp = File(dir, "forge-pack.tmp")
            context.assets.open("forge/forge-pack.apk").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            apk.delete()
            tmp.renameTo(apk)
        }
        apk.setReadOnly() // Android 14+ only loads read-only code files
        return InstalledPack(id, manifest, apk, bundled = true)
    }

    /** Downloaded packs, newest first. */
    fun downloaded(): List<InstalledPack> =
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("dl-") }?.mapNotNull { dir ->
            val m = runCatching { AppJson.decodeFromString(PackManifest.serializer(), File(dir, "manifest.json").readText()) }.getOrNull()
            val apk = File(dir, "forge-pack.apk")
            if (m != null && apk.exists() && compatible(m)) InstalledPack(dir.name, m, apk, bundled = false) else null
        }?.sortedByDescending { it.manifest.forgeDate ?: it.manifest.builtAt ?: "" }.orEmpty()

    fun installed(): List<InstalledPack> = downloaded() + listOfNotNull(bundled())

    /** Picks the selected pack, or the newest downloaded one, or the bundled one. */
    fun refresh(): InstalledPack? {
        val all = installed()
        val wanted = runCatching { selectedFile.readText().trim() }.getOrNull()
        val pick = all.firstOrNull { it.id == wanted } ?: all.firstOrNull()
        _active.value = pick
        return pick
    }

    fun select(id: String) {
        root.mkdirs()
        selectedFile.writeText(id)
        refresh()
    }

    /** A newer compatible pack on GitHub, or null. */
    suspend fun checkRemote(): RemotePack? = withContext(Dispatchers.IO) {
        val json = httpGet("https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG", accept = "application/vnd.github+json")
            ?: return@withContext null
        val assets = AppJson.parseToJsonElement(json).jsonObject["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun url(name: String) = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }
            ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        val manifestUrl = url("manifest.json") ?: return@withContext null
        val apkUrl = url("forge-pack.apk") ?: return@withContext null
        val manifestText = httpGet(manifestUrl) ?: return@withContext null
        val m = AppJson.decodeFromString(PackManifest.serializer(), manifestText)
        if (!compatible(m)) return@withContext null
        val have = installed().map { it.manifest.forgeCommit }.toSet()
        if (m.forgeCommit in have) null else RemotePack(m, apkUrl)
    }

    /** Downloads, verifies and selects a pack. Older downloads beyond the last two are removed. */
    suspend fun download(remote: RemotePack, onProgress: (Float) -> Unit): InstalledPack = withContext(Dispatchers.IO) {
        val id = "dl-${remote.manifest.shortCommit}"
        val dir = File(root, id).apply { deleteRecursively(); mkdirs() }
        val tmp = File(dir, "forge-pack.tmp")
        httpDownload(remote.apkUrl, tmp, remote.manifest.size, onProgress)
        remote.manifest.sha256?.let { expected ->
            val actual = sha256(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                dir.deleteRecursively()
                error("Download was corrupted (checksum mismatch). Try again.")
            }
        }
        val apk = File(dir, "forge-pack.apk")
        tmp.renameTo(apk)
        apk.setReadOnly()
        File(dir, "manifest.json").writeText(AppJson.encodeToString(PackManifest.serializer(), remote.manifest))
        downloaded().drop(KEEP_DOWNLOADED).forEach { File(root, it.id).deleteRecursively() }
        select(id)
        _active.value ?: error("Pack installed but couldn't be selected.")
    }

    /**
     * Unpacks the pack's card data (assets/forge-res.zip inside the pack) once per pack.
     * Returns the folder the engine reads from.
     */
    @Synchronized
    fun unpackResources(pack: InstalledPack, log: (String) -> Unit = {}): File {
        val base = File(context.filesDir, "forge-res")
        val dir = File(base, pack.id)
        if (File(dir, ".complete").exists()) return dir
        log("Unpacking Forge card data (once per engine version)…")
        base.listFiles()?.filter { it.name != pack.id }?.forEach { it.deleteRecursively() }
        dir.deleteRecursively()
        dir.mkdirs()
        ZipFile(pack.apk).use { apk ->
            val entry = apk.getEntry("assets/forge-res.zip") ?: error("This Forge pack has no card data.")
            ZipInputStream(apk.getInputStream(entry).buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        File(dir, ".complete").writeText(pack.id)
        return dir
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
