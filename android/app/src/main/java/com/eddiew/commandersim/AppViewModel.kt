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
    data class Running(
        val progress: Float,
        val status: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val log: List<String> = emptyList(),
        val now: Long = System.currentTimeMillis(),
    ) : SimState
    data class Failed(val message: String, val log: List<String> = emptyList()) : SimState
    data object Done : SimState
}

@Serializable
enum class SimMode { POOL, AB }

@Serializable
enum class SimEngine { SAMPLE, FORGE }

@Serializable
data class SimSettings(
    /** Decks in the pool. In A/B mode these form the field A and B play against. */
    val pool: Set<String> = emptySet(),
    val mode: SimMode = SimMode.POOL,
    val aId: String? = null,
    val bId: String? = null,
    val gamesPerDeck: Int = 500,
    val seatRotation: Boolean = true,
    val engine: SimEngine = SimEngine.SAMPLE,
    /** Forge games are much slower, so they get their own (smaller) game count. */
    val forgeGamesPerDeck: Int = 12,
    /** Forge games played at the same time, each in its own process. */
    val forgeWorkers: Int = 2,
) {
    val effectiveGames: Int get() = if (engine == SimEngine.FORGE) forgeGamesPerDeck else gamesPerDeck
}

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
    private val preconRepo = com.eddiew.commandersim.data.PreconRepository(app.filesDir, linkImporter)
    /** Official Commander precons, cached from MTGJSON. */
    val precons = preconRepo.catalog
    /** True when this build includes Forge's engine and card data. */
    val forgePacks = com.eddiew.commandersim.sim.forge.ForgePacks(app)
    private val workerPool = com.eddiew.commandersim.sim.forge.AndroidForge.WorkerPool(app)
    /** True when a Forge engine (built in or downloaded) is available. */
    val forgeAvailable: Boolean = runCatching { app.assets.open("forge/bundled.txt").close(); true }.getOrDefault(false)

    private val _packOffer = MutableStateFlow<com.eddiew.commandersim.sim.forge.RemotePack?>(null)
    /** A newer Forge engine on GitHub, found at launch or by "Check now". */
    val packOffer: StateFlow<com.eddiew.commandersim.sim.forge.RemotePack?> = _packOffer.asStateFlow()
    private val _packProgress = MutableStateFlow<Float?>(null)
    val packProgress: StateFlow<Float?> = _packProgress.asStateFlow()
    /** True once the user has answered (or dismissed) this launch's Forge update prompt. */
    private val _packPromptDismissed = MutableStateFlow(false)
    val packPromptDismissed: StateFlow<Boolean> = _packPromptDismissed.asStateFlow()

    private val cardInfoCache = com.eddiew.commandersim.data.CardInfoRepository(app.filesDir)
    private var control: com.eddiew.commandersim.sim.SimControl? = null

    val decks = decksRepo.decks
    val report = reportRepo.report
    val history = decksRepo.history

    private val _sim = MutableStateFlow<SimState>(SimState.Idle)
    val sim: StateFlow<SimState> = _sim.asStateFlow()

    private val _settings = MutableStateFlow(SimSettings())
    val settings: StateFlow<SimSettings> = _settings.asStateFlow()

    /** Deck ids currently being looked up on Scryfall. */
    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    private var simJob: Job? = null

    val updater = com.eddiew.commandersim.data.Updater(app)
    private val _update = MutableStateFlow<com.eddiew.commandersim.data.Updater.Release?>(null)
    /** A newer release on GitHub, if one was found. */
    val update: StateFlow<com.eddiew.commandersim.data.Updater.Release?> = _update.asStateFlow()

    /** Returns the newer release, or null if up to date. Throws on network errors. */
    suspend fun checkForUpdate(): com.eddiew.commandersim.data.Updater.Release? =
        updater.checkForUpdate().also { _update.value = it }

    init {
        if (autoLoad) viewModelScope.launch { runCatching { checkForUpdate() } }
        if (autoLoad) viewModelScope.launch(Dispatchers.IO) {
            runCatching { forgePacks.refresh() }
            runCatching { checkForgePack() }
        }
        if (autoLoad) viewModelScope.launch {
            preconRepo.load()
            if (preconRepo.isStale) runCatching { preconRepo.sync() }
        }
        if (autoLoad) viewModelScope.launch {
            decksRepo.load()
            reportRepo.load()
            // Bring decks imported before the current card data level up to date, one at a time.
            launch {
                for (d in decksRepo.decks.value.filter { it.dataLevel < com.eddiew.commandersim.data.model.CARD_DATA_LEVEL }) enrich(d)
            }
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

    fun saveDeck(name: String, preview: ImportPreview, commanderNames: List<String>, owned: Boolean = false): String {
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
            owned = owned,
            syncedAt = if (preview.sourceUrl != null) System.currentTimeMillis() else null,
        )
        viewModelScope.launch {
            decksRepo.upsert(deck)
            decksRepo.snapshot(deck, "Imported")
            updateSettings { it.copy(pool = it.pool + deck.id) }
            enrich(deck)
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
            enrich(variant)
        }
        return variant.id
    }

    fun resync(deckId: String) {
        decks.value.firstOrNull { it.id == deckId }?.let { d -> viewModelScope.launch { enrich(d) } }
    }

    private suspend fun enrich(deck: Deck) {
        _syncing.update { it + deck.id }
        try {
            val names = (deck.commanders + deck.cards).map { it.name }
            val info = scryfall.lookup(names)
            // Functional tags (removal, ramp…) are best effort: a failure here still saves the card data.
            val tags = runCatching { scryfall.tags(names) }.getOrDefault(emptyMap())
            fun fix(list: List<DeckCard>) = list.map { c ->
                val e = ScryfallClient.apply(c, info)
                val t = tags[e.name.lowercase()] ?: tags[c.name.lowercase()]
                e.copy(tags = t?.sorted() ?: e.tags ?: emptyList())
            }
            val latest = decks.value.firstOrNull { it.id == deck.id } ?: deck
            decksRepo.upsert(
                latest.copy(commanders = fix(latest.commanders), cards = fix(latest.cards), enriched = true, dataLevel = com.eddiew.commandersim.data.model.CARD_DATA_LEVEL),
            )
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

    fun setOwned(deckId: String, owned: Boolean) {
        val d = decks.value.firstOrNull { it.id == deckId } ?: return
        viewModelScope.launch { decksRepo.upsert(d.copy(owned = owned)) }
    }

    // ───────────────────────────── Link sync & versions ─────────────────────────────

    data class SyncResult(val deckName: String, val changed: Boolean, val added: Int, val removed: Int, val error: String? = null) {
        val message: String
            get() = when {
                error != null -> "$deckName: $error"
                !changed -> "$deckName: already up to date"
                else -> "$deckName: updated (+$added / −$removed cards). The old list is saved in Versions."
            }
    }

    /** Re-imports a deck from its link. The current list is saved as a version first, so it can be restored. */
    suspend fun syncDeck(deckId: String): SyncResult {
        val d = decks.value.firstOrNull { it.id == deckId } ?: return SyncResult("Deck", false, 0, 0, "not found")
        val url = d.sourceUrl ?: return SyncResult(d.name, false, 0, 0, "wasn't imported from a link")
        _syncing.update { it + d.id }
        try {
            val p = resolveImport(url)
            if (p.cards.isEmpty()) return SyncResult(d.name, false, 0, 0, "the link returned an empty deck")
            // Keep the current commander if the source doesn't say which card it is.
            val all = p.commanders + p.cards
            val commanders = p.commanders.ifEmpty {
                all.filter { c -> d.commanders.any { it.name.equals(c.name, ignoreCase = true) } }
            }
            val cards = all.filterNot { c -> commanders.any { it.name.equals(c.name, ignoreCase = true) } }
            val updated = d.copy(
                commanders = commanders.map { it.copy(quantity = 1) }.ifEmpty { d.commanders },
                cards = cards,
                syncedAt = System.currentTimeMillis(),
                enriched = (commanders + cards).all { it.typeLine != null },
            )
            val diff = com.eddiew.commandersim.data.model.diffDecks(d, updated)
            val changed = diff.added.isNotEmpty() || diff.removed.isNotEmpty()
            if (changed) decksRepo.snapshot(d, "Before sync")
            decksRepo.upsert(if (changed) updated else d.copy(syncedAt = updated.syncedAt))
            if (changed) enrich(updated)
            return SyncResult(d.name, changed, diff.added.sumOf { it.quantity }, diff.removed.sumOf { it.quantity })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncResult(d.name, false, 0, 0, e.message ?: "sync failed")
        } finally {
            _syncing.update { it - d.id }
        }
    }

    suspend fun syncAllLinked(): List<SyncResult> =
        decks.value.filter { it.sourceUrl != null }.map { syncDeck(it.id) }

    /** Switches a deck back to a saved version; the current list is saved as a version first. */
    fun restoreVersion(deckId: String, versionId: String) {
        val d = decks.value.firstOrNull { it.id == deckId } ?: return
        val v = history.value[deckId]?.firstOrNull { it.versionId == versionId } ?: return
        viewModelScope.launch {
            decksRepo.snapshot(d, "Before restoring version")
            decksRepo.upsert(v.asDeck(d))
        }
    }

    // ───────────────────────────── Backup & restore ─────────────────────────────

    fun backupJson(appVersion: String): String = com.eddiew.commandersim.data.BackupCodec.encode(
        com.eddiew.commandersim.data.BackupFile(
            appVersion = appVersion,
            exportedAt = System.currentTimeMillis(),
            decks = decks.value,
            history = history.value,
            settings = _settings.value,
            report = report.value,
        ),
    )

    /** Returns a message for the user. */
    suspend fun restoreBackup(json: String, replace: Boolean): String {
        val b = com.eddiew.commandersim.data.BackupCodec.decode(json)
        val merged = com.eddiew.commandersim.data.BackupCodec.merge(decks.value, history.value, b, replace)
        decksRepo.replaceAll(merged.decks, merged.history)
        val ids = merged.decks.map { it.id }.toSet()
        val s = (if (replace) b.settings else null) ?: _settings.value
        updateSettings { s.copy(pool = (s.pool + if (replace) emptySet() else b.decks.map { it.id }) intersect ids) }
        if (b.report != null && (replace || report.value == null)) reportRepo.save(b.report)
        return if (replace) "Restored ${merged.decks.size} decks from the backup."
        else "Merged backup: ${merged.added} decks added, ${merged.updated} updated."
    }

    // ───────────────────────────── Precons ─────────────────────────────

    /** Refreshes the precon list from MTGJSON. Returns a message for the user. */
    suspend fun syncPrecons(): String = try {
        "Precon list updated: ${preconRepo.sync()} Commander decks."
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Couldn't sync precons: ${e.message}"
    }

    /** Downloads one precon and reads it like an import (commander detected if MTGJSON doesn't say). */
    suspend fun previewPrecon(p: com.eddiew.commandersim.data.Precon): ImportPreview =
        resolveImport(p.url).copy(name = p.name, sourceUrl = p.url)

    // ───────────────────────────── Forge engine packs ─────────────────────────────

    /** Looks for a newer Forge engine on GitHub. Returns it, or null when up to date. */
    suspend fun checkForgePack(): com.eddiew.commandersim.sim.forge.RemotePack? =
        forgePacks.checkRemote().also { _packOffer.value = it }

    fun dismissPackPrompt() { _packPromptDismissed.value = true }

    /** Downloads and switches to the offered pack. Returns a message for the user. */
    suspend fun downloadForgePack(): String {
        val offer = _packOffer.value ?: return "No new Forge engine to download."
        _packPromptDismissed.value = true
        _packProgress.value = 0f
        return try {
            val p = forgePacks.download(offer) { _packProgress.value = it }
            _packOffer.value = null
            workerPool.close() // workers reload with the new engine on the next run
            "Now using ${p.manifest.label}."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Forge download failed: ${e.message}"
        } finally {
            _packProgress.value = null
        }
    }

    fun selectForgePack(id: String) {
        forgePacks.select(id)
        workerPool.close()
    }

    fun setForgeWorkers(n: Int) = updateSettings { it.copy(forgeWorkers = n.coerceIn(1, 4)) }

    // ───────────────────────────── Card info ─────────────────────────────

    suspend fun cardInfo(name: String): com.eddiew.commandersim.data.CardInfo = cardInfoCache.get(name)

    override fun onCleared() {
        workerPool.close()
        super.onCleared()
    }

    // ───────────────────────────── Simulation settings ─────────────────────────────

    fun togglePool(deckId: String) = updateSettings { s ->
        s.copy(pool = if (deckId in s.pool) s.pool - deckId else s.pool + deckId)
    }

    fun setMode(mode: SimMode) = updateSettings { it.copy(mode = mode) }
    fun setA(id: String?) = updateSettings { it.copy(aId = id) }
    fun setB(id: String?) = updateSettings { it.copy(bId = id) }
    fun setGames(n: Int) = updateSettings {
        if (it.engine == SimEngine.FORGE) it.copy(forgeGamesPerDeck = n) else it.copy(gamesPerDeck = n)
    }
    fun setEngine(e: SimEngine) = updateSettings { it.copy(engine = e) }
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
            gamesPerDeck = s.effectiveGames,
            seatRotation = s.seatRotation,
            ab = ab,
        )
        val useForge = s.engine == SimEngine.FORGE && forgeAvailable
        val simulator: SimulationService =
            if (useForge) com.eddiew.commandersim.sim.forge.AndroidForge.simulator(
                getApplication<Application>(), forgePacks, workerPool,
                com.eddiew.commandersim.sim.forge.ForgeOptions(workers = s.forgeWorkers),
            ) else SampleSimulator()
        val ctl = com.eddiew.commandersim.sim.SimControl().also { control = it }
        simJob?.cancel()
        simJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            var progress = 0f
            fun snapshot() = SimState.Running(progress, ctl.status, started, ctl.logLines())
            _sim.value = snapshot()
            // Forge games are long; refresh the status line every second, not just between games.
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    if (_sim.value is SimState.Running) _sim.value = snapshot()
                }
            }
            try {
                val report = simulator.run(config, decks.value, ctl) { p -> progress = p; _sim.value = snapshot() }
                ticker.cancel()
                reportRepo.save(report)
                _sim.value = SimState.Done
                onDone()
            } catch (e: CancellationException) {
                ticker.cancel()
                _sim.value = SimState.Idle
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                ctl.log("Failed: ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.take(6).forEach { ctl.log("   at $it") }
                _sim.value = SimState.Failed("${e.javaClass.simpleName}: ${e.message ?: "simulation failed"}", ctl.logLines())
            }
        }
    }

    fun cancelSimulation() {
        simJob?.cancel()
        _sim.value = SimState.Idle
    }

    /** Stops after the current game and builds results from the games played so far. */
    fun stopEarly() {
        control?.stopRequested = true
        control?.log("Stopping after the current game…")
    }
}
