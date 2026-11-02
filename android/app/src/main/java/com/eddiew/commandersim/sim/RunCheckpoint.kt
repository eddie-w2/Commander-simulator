package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.AppJson
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.SimConfig
import kotlinx.serialization.Serializable
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Everything needed to replay a run's schedule exactly: the same seed, config and deck snapshot give the same games
 * in the same order, so finished games can be kept and only the rest played.
 */
@Serializable
data class RunCheckpoint(
    /** Report id; the finished report replaces the partial one. */
    val id: String,
    val createdAt: Long,
    val seed: Long,
    val config: SimConfig,
    val decks: List<Deck>,
    /** "forge" or "quick". */
    val engine: String,
    val forgeWorkers: Int = 2,
    val plannedGames: Int = 0,
)

/** A run's checkpoint on disk: run.json plus one file per finished game. */
class CheckpointStore(root: File) {
    private val dir = File(root, "checkpoint")
    private val gamesDir = File(dir, "games")
    private val runFile = File(dir, "run.json")

    @Synchronized
    fun start(cp: RunCheckpoint) {
        dir.deleteRecursively()
        gamesDir.mkdirs()
        write(cp)
    }

    @Synchronized
    fun write(cp: RunCheckpoint) {
        dir.mkdirs()
        val tmp = File(dir, "run.json.tmp")
        tmp.writeText(AppJson.encodeToString(RunCheckpoint.serializer(), cp))
        tmp.renameTo(runFile)
    }

    @Synchronized
    fun load(): RunCheckpoint? =
        runCatching { AppJson.decodeFromString(RunCheckpoint.serializer(), runFile.readText()) }.getOrNull()

    /** Saves one finished game (written to a temp file first, so a kill mid-write can't leave a broken game). */
    fun saveGame(index: Int, result: GameResult) {
        gamesDir.mkdirs()
        val tmp = File(gamesDir, "$index.tmp")
        ObjectOutputStream(tmp.outputStream().buffered()).use { it.writeObject(result) }
        tmp.renameTo(File(gamesDir, "$index.bin"))
    }

    /** Finished games by job index; unreadable files are skipped (those games are just played again). */
    fun loadGames(): Map<Int, GameResult> =
        gamesDir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty().mapNotNull { f ->
            val i = f.name.removeSuffix(".bin").toIntOrNull() ?: return@mapNotNull null
            runCatching { ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as GameResult } }.getOrNull()?.let { i to it }
        }.toMap()

    fun gameCount(): Int = gamesDir.listFiles { f -> f.name.endsWith(".bin") }?.size ?: 0

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }
}
