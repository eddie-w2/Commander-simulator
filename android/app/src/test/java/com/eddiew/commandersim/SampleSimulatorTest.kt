package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.AbConfig
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SampleSimulatorTest {

    private fun deck(id: String, commander: String, power: Int, extra: List<DeckCard> = emptyList()): Deck {
        val cards = mutableListOf(DeckCard("Island", 36 - extra.size, typeLine = "Basic Land — Island", manaValue = 0.0))
        repeat(30) { cards += DeckCard("$id Creature $it", typeLine = "Creature — Test", manaValue = (2 + (it + power) % 5).toDouble()) }
        repeat(10) { cards += DeckCard("$id Spell $it", typeLine = if (it % 2 == 0) "Instant" else "Sorcery", manaValue = (1 + it % 4).toDouble()) }
        repeat(23) { cards += DeckCard("$id Rock $it", typeLine = "Artifact", manaValue = 2.0) }
        cards += extra
        return Deck(id, "Deck $id", listOf(DeckCard(commander, typeLine = "Legendary Creature — Test", manaValue = 4.0, colors = "U")), cards, 0L)
    }

    private val decks = listOf(deck("a", "Alpha", 1), deck("b", "Bravo", 2), deck("c", "Charlie", 3), deck("d", "Delta", 4), deck("e", "Echo", 0))

    @Test
    fun `pool report is internally consistent`() = runTest {
        val config = SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 200)
        var lastProgress = 0f
        val report = SampleSimulator(seed = 42).run(config, decks) { lastProgress = it }

        assertEquals(1f, lastProgress)
        assertEquals(5, report.decks.size)
        // 5 decks × 200 games / 4 per pod.
        assertEquals(250, report.meta.games)
        for (d in report.decks) {
            val o = d.outcome
            assertTrue("every deck plays about equally", o.games in 199..201)
            assertTrue(o.winRate in 0.0..1.0)
            assertTrue(o.ci95Low <= o.winRate && o.winRate <= o.ci95High)
            assertEquals("placements cover every game", o.games, o.placements.sum())
            assertEquals(o.wins, o.winTurnHistogram.sum())
            assertEquals("one stat row per unique card", 65, d.cards.size)
            assertTrue(abs(d.damage.avgCombat + d.damage.avgNonCombat - d.damage.avgDealtToPlayers) < 1e-6)
            assertTrue(abs(d.damage.avgTakenCombat + d.damage.avgTakenNonCombat - d.damage.avgTaken) < 1e-6)
            assertEquals(o.wins, d.winLoss.wins)
            assertTrue("damage sources sorted", d.damage.sources.zipWithNext().all { (x, y) -> x.perGame >= y.perGame })
            assertTrue("insights produced", d.insights.isNotEmpty())
        }
        // Total kills can't exceed eliminations.
        val totalKills = report.decks.sumOf { it.damage.killsPerGame * it.outcome.games }
        assertTrue(totalKills <= report.meta.games * 3 + 1)
    }

    @Test
    fun `sample games end in a sensible number of rounds`() = runTest {
        val report = SampleSimulator(seed = 3).run(SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 400), decks) {}
        val timeoutRate = report.meta.timeouts.toDouble() / report.meta.games
        println("avg rounds=${report.meta.avgTurns} timeouts=$timeoutRate")
        assertTrue("timeouts ${timeoutRate}", timeoutRate < 0.10)
        assertTrue("avg rounds ${report.meta.avgTurns}", report.meta.avgTurns in 5.0..13.0)
    }

    @Test
    fun `identical A and B give identical paired results`() = runTest {
        val a = deck("x", "Xeno", 2)
        val b = a.copy(id = "y", name = "Deck y", parentId = "x")
        val all = decks + a + b
        val config = SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 150, ab = AbConfig("x", "y"))
        val report = SampleSimulator(seed = 9).run(config, all) {}
        val ab = assertNotNull(report.ab).let { report.ab!! }
        assertEquals(150, ab.games)
        assertEquals("same deck + same seed = same games", ab.aWins, ab.bWins)
        assertEquals(0, ab.onlyA)
        assertEquals(0, ab.onlyB)
        assertTrue(ab.cardsIn.isEmpty() && ab.cardsOut.isEmpty())
        assertTrue(ab.metrics.isNotEmpty())
        assertEquals("A", report.deck("x")!!.abSide)
        assertEquals("B", report.deck("y")!!.abSide)
    }

    @Test
    fun `A and B differing by swaps report the swapped cards`() = runTest {
        val a = deck("x", "Xeno", 2)
        val b = a.copy(
            id = "y", parentId = "x",
            cards = a.cards.filterNot { it.name == "x Rock 0" } + DeckCard("Swords to Plowshares", typeLine = "Instant", manaValue = 1.0),
        )
        val report = SampleSimulator(seed = 5).run(
            SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 100, ab = AbConfig("x", "y")), decks + a + b,
        ) {}
        val ab = report.ab!!
        assertEquals(listOf("x Rock 0"), ab.cardsOut)
        assertEquals(listOf("Swords to Plowshares"), ab.cardsIn)
        assertTrue(ab.diffLow <= ab.diff && ab.diff <= ab.diffHigh)
    }

    @Test
    fun `wilson interval`() {
        val (lo, hi) = SampleSimulator.wilson(25, 100)
        assertTrue(lo in 0.17..0.18)
        assertTrue(hi in 0.33..0.35)
    }
}
