package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.data.model.SimulationReport

/**
 * Something that turns decks + a config into a [SimulationReport].
 *
 * [SampleSimulator] runs a simplified on-device game model so the app can be built and tested
 * before the Forge sim-runner backend exists. The backend implementation will submit the job
 * to the orchestrator's API and poll for progress, returning the same report shape.
 */
interface SimulationService {
    suspend fun run(config: SimConfig, decks: List<Deck>, control: SimControl = SimControl(), onProgress: (Float) -> Unit): SimulationReport
}

/** Lets the UI stop a long run early and still get a report from the games played so far. */
class SimControl {
    @Volatile var stopRequested: Boolean = false
    /** Short status line for the progress screen (e.g. "Game 2/8 · round 5"). */
    @Volatile var status: String = ""
    /** 1-based number of the game being played, and how many there are in total. */
    @Volatile var gameIndex: Int = 0
    @Volatile var gamesTotal: Int = 0

    private val started = System.currentTimeMillis()
    private val lines = ArrayDeque<String>()

    /** Adds a timestamped line to the run log (also shown as the status). */
    fun log(message: String) {
        val secs = (System.currentTimeMillis() - started) / 1000
        synchronized(lines) {
            lines.addLast("%d:%02d  %s".format(secs / 60, secs % 60, message))
            while (lines.size > 60) lines.removeFirst()
        }
        status = message
    }

    fun logLines(): List<String> = synchronized(lines) { lines.toList() }
}
