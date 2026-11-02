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

/** One deck's line in a saved run, for history lists and cumulative deck stats. */
@kotlinx.serialization.Serializable
data class DeckRunStat(
    val deckId: String,
    val name: String,
    val games: Int,
    val wins: Int,
    val avgPlace: Double,
    val avgDamage: Double,
    /** "A" / "B" in an A/B run. */
    val abSide: String? = null,
    /** Per opponent: games together and wins. */
    val vs: List<VsStat> = emptyList(),
)

@kotlinx.serialization.Serializable
data class VsStat(val opponentId: String, val name: String, val games: Int, val wins: Int)

/** Index entry for a saved run. The full report is in its own file. */
@kotlinx.serialization.Serializable
data class RunSummary(
    val id: String,
    val createdAt: Long,
    val source: String,
    val ab: Boolean,
    val games: Int,
    val plannedGames: Int,
    val decks: List<DeckRunStat>,
    val avgTurns: Double = 0.0,
) {
    val partial: Boolean get() = plannedGames > 0 && games < plannedGames
    val engineLabel: String get() = when (source) { "forge" -> "Forge"; "quick" -> "Quick model"; else -> "Sample" }

    companion object {
        fun of(r: SimulationReport): RunSummary = RunSummary(
            id = r.id, createdAt = r.createdAt, source = r.source, ab = r.ab != null,
            games = r.meta.games, plannedGames = r.plannedGames, avgTurns = r.meta.avgTurns,
            decks = r.decks.map { d ->
                DeckRunStat(
                    deckId = d.deckId, name = d.name, games = d.outcome.games, wins = d.outcome.wins,
                    avgPlace = d.outcome.avgPlace, avgDamage = d.damage.avgDealtToPlayers, abSide = d.abSide,
                    vs = r.matchups.filter { it.deckId == d.deckId && it.gamesTogether > 0 }.map { m ->
                        VsStat(m.opponentId, r.name(m.opponentId), m.gamesTogether, Math.round(m.winRate * m.gamesTogether).toInt())
                    },
                )
            },
        )
    }
}

/**
 * Every finished (or partial) run, newest first. Reports are kept one file each under reports/, with a small index;
 * only the selected report is loaded.
 */
class ReportRepository(private val root: File) {
    private val dir get() = File(root, "reports")
    private val indexFile get() = File(dir, "index.json")
    private val legacyFile get() = File(root, "latest_report.json")
    private val mutex = Mutex()
    private val indexSer = ListSerializer(RunSummary.serializer())

    private val _runs = MutableStateFlow<List<RunSummary>>(emptyList())
    /** Saved runs, most recent first. */
    val runs: StateFlow<List<RunSummary>> = _runs.asStateFlow()
    private val _report = MutableStateFlow<SimulationReport?>(null)
    /** The run being viewed. */
    val report: StateFlow<SimulationReport?> = _report.asStateFlow()

    private fun file(id: String) = File(dir, "$id.json")

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            var index = runCatching { AppJson.decodeFromString(indexSer, indexFile.readText()) }.getOrNull()
            if (index == null) {
                // Rebuild from the report files (first launch of v0.3.05, or a lost index).
                index = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }.orEmpty().mapNotNull { f ->
                    runCatching { RunSummary.of(AppJson.decodeFromString(SimulationReport.serializer(), f.readText())) }.getOrNull()
                }
            }
            // Bring the single report kept by older versions into the history.
            if (legacyFile.exists()) {
                runCatching {
                    val r = AppJson.decodeFromString(SimulationReport.serializer(), legacyFile.readText())
                    file(r.id).writeText(legacyFile.readText())
                    index = index!!.filterNot { it.id == r.id } + RunSummary.of(r)
                }
                legacyFile.delete()
            }
            val sorted = index!!.sortedByDescending { it.createdAt }
            writeIndex(sorted)
            _runs.value = sorted
        }
        sorted0()?.let { select(it.id) }
    }

    private fun sorted0() = _runs.value.firstOrNull()

    private fun writeIndex(list: List<RunSummary>) {
        dir.mkdirs()
        indexFile.writeText(AppJson.encodeToString(indexSer, list))
    }

    /** Saves (or replaces, same id) a run and shows it. */
    suspend fun save(report: SimulationReport) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            file(report.id).writeText(AppJson.encodeToString(SimulationReport.serializer(), report))
            val list = (_runs.value.filterNot { it.id == report.id } + RunSummary.of(report)).sortedByDescending { it.createdAt }
            writeIndex(list)
            _runs.value = list
            _report.value = report
        }
    }

    /** Shows a saved run. Returns false if its file is gone. */
    suspend fun select(id: String): Boolean = withContext(Dispatchers.IO) {
        if (_report.value?.id == id) return@withContext true
        val r = runCatching { AppJson.decodeFromString(SimulationReport.serializer(), file(id).readText()) }.getOrNull()
        if (r != null) _report.value = r
        r != null
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(id).delete()
            val list = _runs.value.filterNot { it.id == id }
            writeIndex(list)
            _runs.value = list
            if (_report.value?.id == id) _report.value = null
        }
        sorted0()?.let { if (_report.value == null) select(it.id) }
    }

    /** Removes every saved run. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles().orEmpty().forEach { it.delete() }
            _runs.value = emptyList()
            _report.value = null
        }
    }
}
