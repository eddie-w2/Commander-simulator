package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckVersion
import com.eddiew.commandersim.data.model.SimulationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Small JSON-file store. Decks and reports are tiny, so whole-file rewrites are fine. */
class DeckRepository(private val dir: File) {
    private val file get() = File(dir, "decks.json")
    private val historyFile get() = File(dir, "deck_history.json")
    private val mutex = Mutex()
    private val _decks = MutableStateFlow<List<Deck>>(emptyList())
    val decks: StateFlow<List<Deck>> = _decks.asStateFlow()

    /** Saved versions per deck id, newest first. */
    private val _history = MutableStateFlow<Map<String, List<DeckVersion>>>(emptyMap())
    val history: StateFlow<Map<String, List<DeckVersion>>> = _history.asStateFlow()

    private val historySerializer = MapSerializer(String.serializer(), ListSerializer(DeckVersion.serializer()))

    suspend fun load() = withContext(Dispatchers.IO) {
        _decks.value = runCatching {
            if (file.exists()) AppJson.decodeFromString(ListSerializer(Deck.serializer()), file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        _history.value = runCatching {
            if (historyFile.exists()) AppJson.decodeFromString(historySerializer, historyFile.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun upsert(deck: Deck) = mutate { list -> list.filterNot { it.id == deck.id } + deck }
    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        mutateHistory { it - id }
    }

    /** Saves a copy of [deck] as it is now, so the change about to be made can be undone. */
    suspend fun snapshot(deck: Deck, reason: String, at: Long = System.currentTimeMillis()): DeckVersion {
        val v = DeckVersion(
            versionId = java.util.UUID.randomUUID().toString(),
            deckId = deck.id, savedAt = at, reason = reason,
            name = deck.name, commanders = deck.commanders, cards = deck.cards,
        )
        mutateHistory { h -> h + (deck.id to (listOf(v) + (h[deck.id] ?: emptyList())).take(MAX_VERSIONS)) }
        return v
    }

    /** Replaces everything (used by restore-from-backup). */
    suspend fun replaceAll(decks: List<Deck>, history: Map<String, List<DeckVersion>>) {
        mutate { decks }
        mutateHistory { history }
    }

    private suspend fun mutate(change: (List<Deck>) -> List<Deck>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _decks.update { change(it).sortedBy { d -> d.name.lowercase() } }
            dir.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(Deck.serializer()), _decks.value))
        }
    }

    private suspend fun mutateHistory(change: (Map<String, List<DeckVersion>>) -> Map<String, List<DeckVersion>>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _history.update(change)
                dir.mkdirs()
                historyFile.writeText(AppJson.encodeToString(historySerializer, _history.value))
            }
        }

    companion object {
        const val MAX_VERSIONS = 25
    }
}

class ReportRepository(private val dir: File) {
    private val file get() = File(dir, "latest_report.json")
    private val _report = MutableStateFlow<SimulationReport?>(null)
    val report: StateFlow<SimulationReport?> = _report.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _report.value = runCatching {
            if (file.exists()) AppJson.decodeFromString(SimulationReport.serializer(), file.readText()) else null
        }.getOrNull()
    }

    suspend fun save(report: SimulationReport) = withContext(Dispatchers.IO) {
        _report.value = report
        dir.mkdirs()
        file.writeText(AppJson.encodeToString(SimulationReport.serializer(), report))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _report.value = null
        file.delete()
    }
}
