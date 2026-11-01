package com.eddiew.commandersim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.data.DeckRepository
import com.eddiew.commandersim.data.ReportRepository
import com.eddiew.commandersim.data.ScryfallClient
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimulationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface SimState {
    data object Idle : SimState
    data class Running(val progress: Float) : SimState
    data class Failed(val message: String) : SimState
    data object Done : SimState
}

data class SimSettings(
    val roles: Map<String, DeckRole> = emptyMap(),
    val gamesPerCandidate: Int = 500,
    val seatRotation: Boolean = true,
)

class AppViewModel(app: Application, autoLoad: Boolean) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, true)

    private val decksRepo = DeckRepository(app.filesDir)
    private val reportRepo = ReportRepository(app.filesDir)
    private val scryfall = ScryfallClient()
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
            // New decks default: first one a candidate, the rest opponents.
            _settings.update { s ->
                s.copy(roles = decksRepo.decks.value.mapIndexed { i, d ->
                    d.id to (s.roles[d.id] ?: if (i == 0) DeckRole.CANDIDATE else DeckRole.OPPONENT)
                }.toMap())
            }
        }
    }

    fun saveDeck(name: String, parsed: DeckParser.Result, commanderNames: List<String>): String {
        val chosen = commanderNames.map { it.lowercase() }.toSet()
        val all = parsed.commanders + parsed.cards
        val commanders = if (chosen.isEmpty()) parsed.commanders else all.filter { it.name.lowercase() in chosen }
        val cards = all.filterNot { c -> commanders.any { it.name.equals(c.name, ignoreCase = true) } }
        val deck = Deck(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { DeckParser.suggestName(parsed) },
            commanders = commanders.map { it.copy(quantity = 1) },
            cards = cards,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            decksRepo.upsert(deck)
            val defaultRole = if (_settings.value.roles.values.none { it == DeckRole.CANDIDATE }) DeckRole.CANDIDATE else DeckRole.OPPONENT
            _settings.update { it.copy(roles = it.roles + (deck.id to defaultRole)) }
            enrich(deck)
        }
        return deck.id
    }

    fun resync(deckId: String) {
        decks.value.firstOrNull { it.id == deckId }?.let { d -> viewModelScope.launch { enrich(d) } }
    }

    private suspend fun enrich(deck: Deck) {
        _syncing.update { it + deck.id }
        try {
            val info = scryfall.lookup((deck.commanders + deck.cards).map { it.name })
            fun fix(list: List<DeckCard>) = list.map { ScryfallClient.apply(it, info) }
            decksRepo.upsert(deck.copy(commanders = fix(deck.commanders), cards = fix(deck.cards), enriched = true))
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
            _settings.update { it.copy(roles = it.roles - id) }
        }
    }

    fun setRole(deckId: String, role: DeckRole?) {
        _settings.update { s -> s.copy(roles = if (role == null) s.roles - deckId else s.roles + (deckId to role)) }
    }

    fun setGames(n: Int) = _settings.update { it.copy(gamesPerCandidate = n) }
    fun setSeatRotation(on: Boolean) = _settings.update { it.copy(seatRotation = on) }

    fun runSimulation(onDone: () -> Unit) {
        val s = _settings.value
        val ids = decks.value.map { it.id }.toSet()
        val config = SimConfig(
            candidateIds = s.roles.filter { it.value == DeckRole.CANDIDATE && it.key in ids }.keys.toList(),
            opponentIds = s.roles.filter { it.value == DeckRole.OPPONENT && it.key in ids }.keys.toList(),
            gamesPerCandidate = s.gamesPerCandidate,
            seatRotation = s.seatRotation,
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
