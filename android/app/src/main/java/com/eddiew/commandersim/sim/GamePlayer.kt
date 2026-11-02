package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.Deck

/** Plays single games for [SampleSimulator]: the quick model or Forge. */
internal interface GamePlayer {
    /** Called once per simulation with every deck that will play. */
    fun prepare(decks: List<Deck>, control: SimControl) {}
    /** Must be safe to call from [parallelism] threads at once. */
    fun play(seats: List<Deck>, seed: Long): GameResult
    /** How many games can run at the same time. */
    val parallelism: Int get() = 1
    /** Called when the simulation ends. */
    fun close() {}
}
