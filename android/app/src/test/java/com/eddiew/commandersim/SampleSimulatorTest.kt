package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSimulatorTest {

    private fun deck(id: String, commander: String, power: Int): Deck {
        val cards = mutableListOf(DeckCard("Island", 36, typeLine = "Basic Land — Island", manaValue = 0.0))
        repeat(30) { cards += DeckCard("$id Creature $it", typeLine = "Creature — Test", manaValue = (2 + (it + power) % 5).toDouble()) }
        repeat(10) { cards += DeckCard("$id Spell $it", typeLine = if (it % 2 == 0) "Instant" else "Sorcery", manaValue = (1 + it % 4).toDouble()) }
        repeat(23) { cards += DeckCard("$id Rock $it", typeLine = "Artifact", manaValue = 2.0) }
        return Deck(id, "Deck $id", listOf(DeckCard(commander, typeLine = "Legendary Creature — Test", manaValue = 4.0, colors = "U")), cards, 0L)
    }

    @Test
    fun `report is internally consistent`() = runTest {
        val decks = listOf(deck("a", "Alpha", 1), deck("b", "Bravo", 2), deck("c", "Charlie", 3), deck("d", "Delta", 4), deck("e", "Echo", 0))
        val config = SimConfig(candidateIds = listOf("a", "b"), opponentIds = listOf("c", "d", "e"), gamesPerCandidate = 200)
        var lastProgress = 0f
        val report = SampleSimulator(seed = 42).run(config, decks) { lastProgress = it }

        assertEquals(1f, lastProgress)
        assertEquals(400, report.meta.games)
        assertEquals(2, report.rankedCandidates.size)
        for (d in report.decks) {
            val o = d.outcome
            assertTrue("win rate in range", o.winRate in 0.0..1.0)
            assertTrue("CI brackets rate", o.ci95Low <= o.winRate && o.winRate <= o.ci95High)
            assertEquals("placements cover every game", o.games, o.placements.sum())
            assertEquals("wins histogram", o.wins, o.winTurnHistogram.sum())
            assertTrue("draws cards", d.resources.avgCardsDrawn > 0)
            assertEquals("one stat row per unique card", 65, d.cards.size)
            assertTrue("damage split adds up",
                kotlin.math.abs(d.damage.avgCombat + d.damage.avgNonCombat - d.damage.avgDealtToPlayers) < 1e-6)
        }
        // Candidates each played 200 games.
        report.rankedCandidates.forEach { assertEquals(200, it.outcome.games) }
    }

    @Test
    fun `wilson interval`() {
        val (lo, hi) = SampleSimulator.wilson(25, 100)
        assertTrue(lo in 0.17..0.18)
        assertTrue(hi in 0.33..0.35)
    }
}
