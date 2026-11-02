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
    /** Short status line for the progress screen (e.g. "Loading card data…"). */
    @Volatile var status: String = ""
}
