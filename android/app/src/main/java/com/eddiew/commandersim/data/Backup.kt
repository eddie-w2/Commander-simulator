package com.eddiew.commandersim.data

import com.eddiew.commandersim.SimSettings
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckVersion
import com.eddiew.commandersim.data.model.SimulationReport
import kotlinx.serialization.Serializable

/** Everything the app stores, as one JSON file the user can save anywhere. */
@Serializable
data class BackupFile(
    val format: Int = FORMAT,
    val appVersion: String,
    val exportedAt: Long,
    val decks: List<Deck>,
    val history: Map<String, List<DeckVersion>> = emptyMap(),
    val settings: SimSettings? = null,
    val report: SimulationReport? = null,
) {
    companion object {
        const val FORMAT = 1
    }
}

object BackupCodec {
    fun encode(b: BackupFile): String = AppJson.encodeToString(BackupFile.serializer(), b)

    /** Throws [IllegalArgumentException] with a readable message when the file isn't a backup. */
    fun decode(text: String): BackupFile {
        val b = runCatching { AppJson.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("That file isn't a Commander Sim backup.") }
        require(b.format <= BackupFile.FORMAT) { "This backup was made by a newer version of the app." }
        return b
    }

    data class Merged(val decks: List<Deck>, val history: Map<String, List<DeckVersion>>, val added: Int, val updated: Int)

    /**
     * [replace] = true: the backup becomes the whole library.
     * Otherwise decks are merged by id: decks only on the phone are kept, decks in the backup are added or overwrite
     * the phone's copy (the phone's copy is kept in that deck's version history first).
     */
    fun merge(
        current: List<Deck>,
        currentHistory: Map<String, List<DeckVersion>>,
        backup: BackupFile,
        replace: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Merged {
        if (replace) return Merged(backup.decks, backup.history, backup.decks.size, 0)
        val byId = current.associateBy { it.id }.toMutableMap()
        val history = currentHistory.toMutableMap()
        var added = 0
        var updated = 0
        for (d in backup.decks) {
            val existing = byId[d.id]
            if (existing == null) added++ else if (existing != d) {
                updated++
                val v = DeckVersion(java.util.UUID.randomUUID().toString(), d.id, now, "Before restoring backup",
                    existing.name, existing.commanders, existing.cards)
                history[d.id] = listOf(v) + (history[d.id] ?: emptyList())
            }
            byId[d.id] = d
        }
        for ((id, versions) in backup.history) {
            val mine = history[id] ?: emptyList()
            history[id] = (mine + versions.filter { v -> mine.none { it.versionId == v.versionId } })
                .sortedByDescending { it.savedAt }.take(DeckRepository.MAX_VERSIONS)
        }
        return Merged(byId.values.toList(), history, added, updated)
    }
}
