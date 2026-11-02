package com.eddiew.commandersim.data

import com.eddiew.commandersim.sim.SampleSimulator

/** How one deck has done across every saved run it played in. */
data class DeckHistory(
    /** Runs with this deck, most recent first. */
    val runs: List<Pair<RunSummary, DeckRunStat>>,
    val games: Int,
    val wins: Int,
    val avgPlace: Double?,
    val avgDamage: Double?,
    /** Engine label → (games, wins). */
    val byEngine: Map<String, Pair<Int, Int>>,
    /** Per opponent across all runs, most games first. */
    val vs: List<VsStat>,
    /** Win rate per run, oldest first (for a trend line). */
    val trend: List<Double>,
    /** Expected win rate for an average deck at the usual table size. */
    val even: Double,
) {
    val winRate: Double get() = if (games > 0) wins.toDouble() / games else 0.0
    val ci: Pair<Double, Double> get() = SampleSimulator.wilson(wins, games)

    companion object {
        fun of(all: List<RunSummary>, deckId: String): DeckHistory {
            val runs = all.mapNotNull { r -> r.decks.firstOrNull { it.deckId == deckId }?.let { r to it } }.filter { it.second.games > 0 }
            val games = runs.sumOf { it.second.games }
            val wins = runs.sumOf { it.second.wins }
            fun weighted(f: (DeckRunStat) -> Double) = if (games > 0) runs.sumOf { f(it.second) * it.second.games } / games else null
            val byEngine = runs.groupBy { it.first.engineLabel }
                .mapValues { (_, l) -> l.sumOf { it.second.games } to l.sumOf { it.second.wins } }
            val vs = runs.flatMap { it.second.vs }.groupBy { it.opponentId }.map { (id, l) ->
                VsStat(id, l.last().name, l.sumOf { it.games }, l.sumOf { it.wins })
            }.sortedByDescending { it.games }
            // Most runs use 4-player pods; smaller pools play smaller pods.
            val seats = runs.map { (r, _) -> minOf(4, if (r.ab) r.decks.size - 1 else r.decks.size).coerceAtLeast(2) }
            val even = if (seats.isEmpty()) 0.25 else seats.map { 1.0 / it }.average()
            return DeckHistory(
                runs = runs, games = games, wins = wins,
                avgPlace = weighted { it.avgPlace }, avgDamage = weighted { it.avgDamage },
                byEngine = byEngine, vs = vs,
                trend = runs.reversed().map { it.second.wins.toDouble() / it.second.games },
                even = even,
            )
        }
    }
}
