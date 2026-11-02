package com.eddiew.commandersim.sim.forge

import android.content.Context
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.sim.GamePlayer
import com.eddiew.commandersim.sim.GameResult
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimControl
import com.eddiew.commandersim.sim.SimulationService
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks Forge's card data from the APK and builds simulators that use the real Forge engine. */
object AndroidForge {
    private const val ASSET = "forge/forge-res.zip"

    /** True if this build includes the Forge engine data. */
    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.open(ASSET).close(); true }.getOrDefault(false)

    /**
     * Unpacks the bundle into app storage once per Forge version (about 25 MB), removing older versions.
     * Returns the folder to pass to [ForgeEngine].
     */
    @Synchronized
    fun ensureUnpacked(context: Context, status: (String) -> Unit = {}): File {
        val base = File(context.filesDir, "forge-res")
        val version = context.assets.open(ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == "VERSION" }?.let { zip.readBytes().toString(Charsets.UTF_8).trim() }
            }
        } ?: "unknown"
        val dir = File(base, version)
        if (File(dir, ".complete").exists()) return dir
        status("Unpacking Forge card data (first run only)…")
        base.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        context.assets.open(ASSET).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
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
        File(dir, ".complete").writeText(version)
        return dir
    }

    /** A simulator that plays every game with Forge's rules engine and AI. */
    fun simulator(context: Context, seed: Long = System.currentTimeMillis()): SimulationService {
        val player = LazyForgePlayer(context.applicationContext)
        return SampleSimulator(seed, player, "forge") { player.notes() }
    }

    /** Defers unpacking and engine start-up until the simulation actually begins. */
    private class LazyForgePlayer(private val context: Context) : GamePlayer {
        private var engine: ForgeEngine? = null
        var control: SimControl? = null

        override fun prepare(decks: List<Deck>, control: SimControl) {
            val res = ensureUnpacked(context) { control.log(it) }
            val work = File(context.cacheDir, "forge-work")
            engine = ForgeEngine(res, work).also { it.prepare(decks, control) }
        }

        override fun play(seats: List<Deck>, seed: Long): GameResult =
            (engine ?: error("Forge engine not prepared")).play(seats, seed)

        fun notes(): List<String> {
            val missing = engine?.missingCards.orEmpty()
            return listOfNotNull(
                "Played with the Forge rules engine and Forge AI.",
                if (missing.isEmpty()) null
                else "Forge doesn't know ${missing.size} card${if (missing.size > 1) "s" else ""}, so they were left out: " +
                    missing.take(12).joinToString(", ") + if (missing.size > 12) "…" else "",
            )
        }
    }
}
