package com.eddiew.commandersim.forgepack

import com.eddiew.commandersim.sim.BridgeDeck
import com.eddiew.commandersim.sim.ForgeBridge
import com.eddiew.commandersim.sim.GameResult
import java.io.File

/**
 * Entry point the app loads from the pack by name. One instance per worker process; the engine (and its card
 * database) is kept for as long as the process lives.
 */
class Bridge : ForgeBridge {
    private var engine: ForgeEngine? = null
    private var enginePaths: Pair<String, String>? = null

    override fun forgeVersion(resRoot: File): String =
        File(resRoot, "VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "unknown"

    override fun prepare(resRoot: File, workDir: File, decks: List<BridgeDeck>, log: (String) -> Unit): List<String> {
        val paths = resRoot.path to workDir.path
        val e = engine?.takeIf { enginePaths == paths } ?: ForgeEngine(resRoot, workDir).also {
            engine = it
            enginePaths = paths
        }
        e.prepare(decks, log)
        return e.missingCards
    }

    override fun play(seatDeckIds: List<String>, seed: Long, maxRounds: Int, maxMillis: Long, status: (String) -> Unit): GameResult =
        (engine ?: error("Forge engine not prepared")).play(seatDeckIds, seed, maxRounds, maxMillis, status)

    /** Recorder diagnostics from the last game (used by tests). */
    fun lastDiagnostics(): String = engine?.lastDiagnostics.orEmpty()
}
