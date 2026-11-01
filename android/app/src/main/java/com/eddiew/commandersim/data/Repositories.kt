package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.Deck
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
    private val mutex = Mutex()
    private val _decks = MutableStateFlow<List<Deck>>(emptyList())
    val decks: StateFlow<List<Deck>> = _decks.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _decks.value = runCatching {
            if (file.exists()) AppJson.decodeFromString(ListSerializer(Deck.serializer()), file.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(deck: Deck) = mutate { list -> list.filterNot { it.id == deck.id } + deck }
    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(change: (List<Deck>) -> List<Deck>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _decks.update { change(it).sortedBy { d -> d.name.lowercase() } }
            dir.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(Deck.serializer()), _decks.value))
        }
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
}
