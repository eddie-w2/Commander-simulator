package com.eddiew.commandersim

import android.app.Application
import com.eddiew.commandersim.data.DeckRepository
import com.eddiew.commandersim.data.ReportRepository
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.CheckpointStore
import com.eddiew.commandersim.sim.GameResult
import com.eddiew.commandersim.sim.RunCheckpoint
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimControl
import com.eddiew.commandersim.sim.forge.AndroidForge
import com.eddiew.commandersim.sim.forge.ForgeOptions
import com.eddiew.commandersim.sim.forge.ForgePacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * App-wide objects that must outlive the screen: repositories, the Forge worker pool and the simulation runner.
 * A run keeps going while the app is in the background (a foreground service holds the process) and survives the
 * activity being destroyed.
 */
class AppServices private constructor(val app: Application) {
    val decks = DeckRepository(app.filesDir)
    val reports = ReportRepository(app.filesDir)
    val forgePacks = ForgePacks(app)
    val workerPool = AndroidForge.WorkerPool(app)
    val checkpoints = CheckpointStore(app.filesDir)
    val runner = SimRunner(this)

    companion object {
        @Volatile private var instance: AppServices? = null
        fun get(app: Application): AppServices = instance ?: synchronized(this) { instance ?: AppServices(app).also { instance = it } }
    }
}

/** An interrupted (or stopped) run that can be continued. */
data class ResumableRun(val id: String, val createdAt: Long, val engine: String, val done: Int, val planned: Int, val deckNames: List<String>)

class SimRunner(private val services: AppServices) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SimState>(SimState.Idle)
    val state: StateFlow<SimState> = _state.asStateFlow()
    private val _resumable = MutableStateFlow<ResumableRun?>(null)
    /** A checkpointed run with games left to play, if any. */
    val resumable: StateFlow<ResumableRun?> = _resumable.asStateFlow()

    private var job: Job? = null
    private var control: SimControl? = null

    val isRunning: Boolean get() = _state.value is SimState.Running

    /** Starts a new run. Forge runs are checkpointed after every game. */
    fun start(config: SimConfig, decks: List<Deck>, forge: Boolean, forgeWorkers: Int) {
        if (isRunning) return
        val used = (config.deckIds + listOfNotNull(config.ab?.aId, config.ab?.bId)).toSet()
        val cp = RunCheckpoint(
            id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(), seed = System.currentTimeMillis(),
            config = config, decks = decks.filter { it.id in used }, engine = if (forge) "forge" else "quick",
            forgeWorkers = forgeWorkers,
        )
        if (forge) {
            services.checkpoints.start(cp)
            _resumable.value = null
        }
        launch(cp, resumed = emptyMap(), replayOnly = false)
    }

    /** Continues the checkpointed run: saved games are kept, the rest are played. */
    fun resume() {
        if (isRunning) return
        val cp = services.checkpoints.load() ?: run { _resumable.value = null; return }
        val games = services.checkpoints.loadGames()
        _resumable.value = null
        launch(cp, games, replayOnly = false)
    }

    /** Forgets the checkpoint (the partial results stay in the run history). */
    fun discardResumable() {
        services.checkpoints.clear()
        _resumable.value = null
    }

    fun stopEarly() {
        control?.stopRequested = true
        control?.log("Stopping after the current game…")
    }

    fun cancel() {
        job?.cancel()
        _state.value = SimState.Idle
    }

    fun acknowledge() {
        if (_state.value !is SimState.Running) _state.value = SimState.Idle
    }

    /**
     * At app start: if a checkpointed run was cut off (app killed), rebuild a partial report from its saved games so
     * nothing is lost, and offer to resume it.
     */
    suspend fun recover() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        val cp = services.checkpoints.load() ?: return@withContext
        val games = services.checkpoints.loadGames()
        if (games.isEmpty()) {
            // Nothing finished yet: nothing to keep, but it can still be started again.
            _resumable.value = ResumableRun(cp.id, cp.createdAt, cp.engine, 0, cp.plannedGames, cp.decks.map { it.name })
            return@withContext
        }
        val saved = services.reports.runs.value.firstOrNull { it.id == cp.id }
        if (saved == null || saved.games < games.size) {
            runCatching {
                val ctl = SimControl().apply { stopRequested = true }
                val report = simulator(cp, games, ctl).run(cp.config, cp.decks, ctl) {}
                services.reports.save(report.copy(createdAt = cp.createdAt))
            }
        }
        val planned = services.reports.runs.value.firstOrNull { it.id == cp.id }?.plannedGames ?: cp.plannedGames
        if (planned > 0 && games.size >= planned) {
            services.checkpoints.clear()
        } else {
            _resumable.value = ResumableRun(cp.id, cp.createdAt, cp.engine, games.size, planned, cp.decks.map { it.name })
        }
    }

    private fun simulator(cp: RunCheckpoint, resumed: Map<Int, GameResult>, ctl: SimControl): SampleSimulator {
        val onGame: (Int, GameResult) -> Unit = if (cp.engine == "forge") { i, r -> services.checkpoints.saveGame(i, r) } else { _, _ -> }
        return if (cp.engine == "forge") {
            AndroidForge.simulator(
                services.app, services.forgePacks, services.workerPool, ForgeOptions(workers = cp.forgeWorkers),
                seed = cp.seed, reportId = cp.id, resumed = resumed, onGame = onGame,
            )
        } else {
            SampleSimulator(cp.seed, cp.id)
        }
    }

    private fun launch(cp: RunCheckpoint, resumed: Map<Int, GameResult>, replayOnly: Boolean) {
        val ctl = SimControl().also { control = it; it.stopRequested = replayOnly }
        job?.cancel()
        val started = System.currentTimeMillis()
        var progress = 0f
        fun snapshot() = SimState.Running(progress, ctl.status, started, ctl.logLines(), total = ctl.gamesTotal)
        _state.value = snapshot()
        SimRunService.start(services.app)
        job = scope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    if (_state.value is SimState.Running) _state.value = snapshot()
                }
            }
            try {
                val report = simulator(cp, resumed, ctl).run(cp.config, cp.decks, ctl) { p -> progress = p; _state.value = snapshot() }
                    .copy(createdAt = cp.createdAt)
                services.reports.save(report)
                if (cp.engine == "forge") {
                    if (report.partial) {
                        // Record the plan so the run can be resumed after an app restart, too.
                        services.checkpoints.write(cp.copy(plannedGames = report.plannedGames))
                        _resumable.value = ResumableRun(cp.id, cp.createdAt, cp.engine, report.meta.games, report.plannedGames, cp.decks.map { it.name })
                    } else {
                        services.checkpoints.clear()
                    }
                }
                _state.value = SimState.Done(report.id)
            } catch (e: CancellationException) {
                _state.value = SimState.Idle
                throw e
            } catch (e: Throwable) {
                ctl.log("Failed: ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.take(6).forEach { ctl.log("   at $it") }
                _state.value = SimState.Failed("${e.javaClass.simpleName}: ${e.message ?: "simulation failed"}", ctl.logLines())
                // Keep whatever finished before the failure, and offer to resume.
                if (cp.engine == "forge") {
                    if (services.checkpoints.load() == null) services.checkpoints.start(cp)
                    runCatching { recover() }
                }
            } finally {
                ticker.cancel()
            }
        }
    }
}
