package com.eddiew.commandersim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eddiew.commandersim.data.AppJson
import com.eddiew.commandersim.data.DeckLinkImporter
import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.data.DeckRepository
import com.eddiew.commandersim.data.ReportRepository
import com.eddiew.commandersim.data.ScryfallClient
import com.eddiew.commandersim.data.model.AbConfig
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimulationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface SimState {
    data object Idle : SimState
    data class Running(val progress: Float) : SimState
    data class Failed(val message: String) : SimState
    data object Done : SimState
}

@Serializable
enum class SimMode { POOL, AB }

@Serializable
data class SimSettings(
    /** Decks in the pool. In A/B mode these form the field A and B play against. */
    val pool: Set<String> = emptySet(),
    val mode: SimMode = SimMode.POOL,
    val aId: String? = null,
    val bId: String? = null,
    val gamesPerDeck: Int = 500,
    val seatRotation: Boolean = true,
)

/** Result of reading a pasted list or link on the import screen. */
data class ImportPreview(
    val name: String,
    val commanders: List<DeckCard>,
    val cards: List<DeckCard>,
    val warnings: List<String>,
    val ignored: Int,
    /** How the commander was found, shown to the user. */
    val commanderNote: String?,
    val sourceUrl: String? = null,
) {
    val total: Int get() = commanders.sumOf { it.quantity } + cards.sumOf { it.quantity }
}

class AppViewModel(app: Application, autoLoad: Boolean) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, true)

    private val decksRepo = DeckRepository(app.filesDir)
    private val reportRepo = ReportRepository(app.filesDir)
    private val settingsFile = File(app.filesDir, "settings.json")
    private val scryfall = ScryfallClient()
    private val linkImporter = DeckLinkImporter()
    private val simulator: SimulationService = SampleSimulator()

    val decks = decksRepo.decks
    val report = reportRepo.report

    private val _sim = MutableStateFlow<SimState>(SimState.Idle)
    val sim: StateFlow<SimState> = _sim.asStateFlow()

    private val _settings = MutableStateFlow(SimSettings())
    val settings: StateFlow<SimSettings> = _settings.asStateFlow()

    /** Deck ids currently being looked up on Scryfall. */
    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    private var simJob: Job? = null

    init {
        if (autoLoad) viewModelScope.launch {
            decksRepo.load()
            reportRepo.load()
            val saved = withContext(Dispatchers.IO) {
                runCatching { AppJson.decodeFromString(SimSettings.serializer(), settingsFile.readText()) }.getOrNull()
            }
            val ids = decksRepo.decks.value.map { it.id }.toSet()
            _settings.value = (saved ?: SimSettings(pool = ids)).let { s ->
                s.copy(pool = s.pool intersect ids, aId = s.aId?.takeIf { it in ids }, bId = s.bId?.takeIf { it in ids })
            }
        }
    }

    private fun updateSettings(change: (SimSettings) -> SimSettings) {
        _settings.update(change)
        val snapshot = _settings.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { settingsFile.writeText(AppJson.encodeToString(SimSettings.serializer(), snapshot)) }
        }
    }

    // ───────────────────────────── Import ─────────────────────────────

    /** Reads a pasted list (instant, offline). Commander detection that needs card data happens in [resolveImport]. */
    fun previewText(text: String): ImportPreview {
        val r = DeckParser.parse(text)
        return ImportPreview(
            name = DeckParser.suggestName(r),
            commanders = r.commanders,
            cards = r.cards,
            warnings = r.warnings,
            ignored = r.ignored.size,
            commanderNote = when {
                r.commanders.isEmpty() -> null
                r.commanderInferred -> "Found from the list layout"
                else -> "Marked in the list"
            },
        )
    }

    /**
     * Completes an import: fetches a link if given, then, if no commander is known yet, looks the cards up on
     * Scryfall and picks the legendary card(s) whose colour identity covers the deck (99+1 or 98+2).
     */
    suspend fun resolveImport(textOrLink: String): ImportPreview {
        val base = if (DeckLinkImporter.isDeckLink(textOrLink)) {
            val d = linkImporter.import(textOrLink)
            val r = DeckParser.Result(d.commanders, d.cards, emptyList(), emptyList())
            ImportPreview(
                name = d.name, commanders = d.commanders, cards = d.cards,
                warnings = DeckParser.validate(r), ignored = 0,
                commanderNote = if (d.commanders.isNotEmpty()) "From the deck's commander zone" else null,
                sourceUrl = d.url,
            )
        } else previewText(textOrLink)
        if (base.commanders.isNotEmpty() || base.cards.isEmpty()) return base

        val all = base.cards
        val info = runCatching { scryfall.lookup(all.map { it.name }) }.getOrNull() ?: return base
        val facts = info.mapValues { it.value.facts() }
        val found = DeckParser.detectCommander(all, facts) ?: return base
        val rest = all.filterNot { c -> found.any { it.name == c.name } }
        val r = DeckParser.Result(found, rest, emptyList(), emptyList())
        return base.copy(
            name = if (base.sourceUrl == null) DeckParser.suggestName(r) else base.name,
            commanders = found.map { ScryfallClient.apply(it, info) },
            cards = rest.map { ScryfallClient.apply(it, info) },
            warnings = DeckParser.validate(r),
            commanderNote = "Detected: the only legendary card that covers the deck's colours",
        )
    }

    /** Legendary cards in the list, for the manual commander picker (empty if Scryfall is unreachable). */
    suspend fun commanderOptions(cards: List<DeckCard>): List<String> {
        val info = runCatching { scryfall.lookup(cards.map { it.name }) }.getOrNull() ?: return emptyList()
        return cards.filter { c -> info[c.name.lowercase()]?.canBeCommander == true }.map { it.name }
    }

    fun saveDeck(name: String, preview: ImportPreview, commanderNames: List<String>): String {
        val chosen = commanderNames.map { it.lowercase() }.toSet()
        val all = preview.commanders + preview.cards
        val commanders = if (chosen.isEmpty()) preview.commanders else all.filter { it.name.lowercase() in chosen }
        val cards = all.filterNot { c -> commanders.any { it.name.equals(c.name, ignoreCase = true) } }
        val deck = Deck(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { preview.name },
            commanders = commanders.map { it.copy(quantity = 1) },
            cards = cards,
            createdAt = System.currentTimeMillis(),
            enriched = all.all { it.typeLine != null },
            sourceUrl = preview.sourceUrl,
        )
        viewModelScope.launch {
            decksRepo.upsert(deck)
            updateSettings { it.copy(pool = it.pool + deck.id) }
            if (!deck.enriched) enrich(deck)
        }
        return deck.id
    }

    /** Saves a copy of [base] with [remove] taken out and [add] put in, for A/B testing. */
    fun saveVariant(base: Deck, name: String, remove: Map<String, Int>, add: List<DeckCard>): String {
        val kept = base.cards.mapNotNull { c ->
            val left = c.quantity - (remove[c.name] ?: 0)
            if (left > 0) c.copy(quantity = left) else null
        }
        val merged = LinkedHashMap<String, DeckCard>()
        (kept + add).forEach { c ->
            val k = c.name.lowercase()
            merged[k] = merged[k]?.let { it.copy(quantity = it.quantity + c.quantity) } ?: c
        }
        val variant = base.copy(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "${base.name} (B)" },
            cards = merged.values.toList(),
            createdAt = System.currentTimeMillis(),
            enriched = merged.values.all { it.typeLine != null },
            parentId = base.id,
        )
        viewModelScope.launch {
            decksRepo.upsert(variant)
            updateSettings { it.copy(mode = SimMode.AB, aId = base.id, bId = variant.id) }
            if (!variant.enriched) enrich(variant)
        }
        return variant.id
    }

    fun resync(deckId: String) {
        decks.value.firstOrNull { it.id == deckId }?.let { d -> viewModelScope.launch { enrich(d) } }
    }

    private suspend fun enrich(deck: Deck) {
        _syncing.update { it + deck.id }
        try {
            val info = scryfall.lookup((deck.commanders + deck.cards).map { it.name })
            fun fix(list: List<DeckCard>) = list.map { ScryfallClient.apply(it, info) }
            val latest = decks.value.firstOrNull { it.id == deck.id } ?: deck
            decksRepo.upsert(latest.copy(commanders = fix(latest.commanders), cards = fix(latest.cards), enriched = true))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or Scryfall unavailable: the deck still works, cards just show as "Unknown" type.
        } finally {
            _syncing.update { it - deck.id }
        }
    }

    fun deleteDeck(id: String) {
        viewModelScope.launch {
            decksRepo.delete(id)
            updateSettings { s ->
                s.copy(pool = s.pool - id, aId = s.aId?.takeIf { it != id }, bId = s.bId?.takeIf { it != id })
            }
        }
    }

    // ───────────────────────────── Simulation settings ─────────────────────────────

    fun togglePool(deckId: String) = updateSettings { s ->
        s.copy(pool = if (deckId in s.pool) s.pool - deckId else s.pool + deckId)
    }

    fun setMode(mode: SimMode) = updateSettings { it.copy(mode = mode) }
    fun setA(id: String?) = updateSettings { it.copy(aId = id) }
    fun setB(id: String?) = updateSettings { it.copy(bId = id) }
    fun setGames(n: Int) = updateSettings { it.copy(gamesPerDeck = n) }
    fun setSeatRotation(on: Boolean) = updateSettings { it.copy(seatRotation = on) }

    fun startAb(aId: String, bId: String) = updateSettings {
        it.copy(mode = SimMode.AB, aId = aId, bId = bId)
    }

    fun runSimulation(onDone: () -> Unit) {
        val s = _settings.value
        val ids = decks.value.map { it.id }.toSet()
        val ab = if (s.mode == SimMode.AB && s.aId in ids && s.bId in ids) AbConfig(s.aId!!, s.bId!!) else null
        val config = SimConfig(
            deckIds = s.pool.filter { it in ids && it != ab?.aId && it != ab?.bId },
            gamesPerDeck = s.gamesPerDeck,
            seatRotation = s.seatRotation,
            ab = ab,
        )
        simJob?.cancel()
        simJob = viewModelScope.launch {
            _sim.value = SimState.Running(0f)
            try {
                val report = simulator.run(config, decks.value) { p -> _sim.value = SimState.Running(p) }
                reportRepo.save(report)
                _sim.value = SimState.Done
                onDone()
            } catch (e: CancellationException) {
                _sim.value = SimState.Idle
                throw e
            } catch (e: Exception) {
                _sim.value = SimState.Failed(e.message ?: "Simulation failed")
            }
        }
    }

    fun cancelSimulation() {
        simJob?.cancel()
        _sim.value = SimState.Idle
    }
}
