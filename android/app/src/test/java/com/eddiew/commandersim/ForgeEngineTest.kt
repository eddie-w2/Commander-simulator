package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.forge.ForgeEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Plays real Forge games. Runs only when FORGE_RES points at an unpacked forge-res bundle
 * (CI sets it after tools/build-forge.sh); skipped otherwise.
 */
class ForgeEngineTest {
    private val res = System.getenv("FORGE_RES")?.let { File(it) }

    private fun deck(id: String, commander: String, land: String, vararg cards: Pair<String, Int>): Deck {
        val list = cards.map { (n, q) -> DeckCard(n, q) }
        val lands = 99 - list.sumOf { it.quantity }
        return Deck(id, "Deck $id", listOf(DeckCard(commander)), list + DeckCard(land, lands), 0L)
    }

    private val decks = listOf(
        deck("w", "Isamaru, Hound of Konda", "Plains", "Savannah Lions" to 20, "Elite Vanguard" to 20),
        deck("r", "Zurgo Bellstriker", "Mountain", "Raging Goblin" to 20, "Shock" to 10, "Goblin Piker" to 10),
        deck("g", "Rhys the Exiled", "Forest", "Grizzly Bears" to 20, "Llanowar Elves" to 10, "Giant Growth" to 10),
        deck("u", "Talrand, Sky Summoner", "Island", "Merfolk of the Pearl Trident" to 20, "Counterspell" to 10, "Lord of Atlantis" to 10),
    )

    @Test
    fun `forge plays a four player commander game and records stats`() {
        assumeTrue("FORGE_RES not set", res != null && res.isDirectory)
        val engine = ForgeEngine(res!!, Files.createTempDirectory("forge-work").toFile(), maxRounds = 20, maxMillisPerGame = 120_000)
        engine.prepare(decks)
        assertTrue("all test cards known: ${engine.missingCards}", engine.missingCards.isEmpty())
        val start = System.currentTimeMillis()
        val r = engine.play(decks, seed = 1L)
        println("Forge game: rounds=${r.rounds} winner=${r.winnerSeat} cause=${r.winCondition} in ${System.currentTimeMillis() - start} ms")
        r.tracks.forEachIndexed { i, t ->
            println("  seat $i place=${t.place} turns=${t.turnsTaken} drawn=${t.drawn} dmg=${t.dmgPlayers} taken=${t.taken} lands=${t.landsPlayed} casts=${t.cards.values.sumOf { it.casts }} kills=${t.kills}")
        }
        assertTrue("game lasted some turns", r.rounds >= 2)
        assertTrue("players took turns", r.tracks.all { it.turnsTaken >= 1 })
        assertTrue("lands were played", r.tracks.sumOf { it.landsPlayed } > 0)
        assertTrue("spells were cast", r.tracks.sumOf { t -> t.cards.values.sumOf { it.casts } } > 0)
        assertEquals(4, r.tracks.size)
        assertTrue("turn order recorded", r.turnPosition != null)
    }

    @Test
    fun `forge pool simulation produces a report`() = runTest(timeout = 15.minutes) {
        assumeTrue("FORGE_RES not set", res != null && res.isDirectory)
        val engine = ForgeEngine(res!!, Files.createTempDirectory("forge-work").toFile(), maxRounds = 20, maxMillisPerGame = 120_000)
        val sim = SampleSimulator(7L, engine, "forge")
        val report = sim.run(SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 3), decks) {}
        println("Forge pool: games=${report.meta.games} avgRounds=${report.meta.avgTurns} avgMs=${report.meta.avgGameMs}")
        report.decks.forEach { println("  ${it.name}: wr=${it.outcome.winRate} dmg=${it.damage.avgDealtToPlayers} drawn=${it.resources.avgCardsDrawn}") }
        assertEquals("forge", report.source)
        assertEquals(3, report.meta.games)
        assertTrue(report.decks.all { it.outcome.games == 3 })
        assertTrue("damage recorded", report.decks.sumOf { it.damage.avgDealtToPlayers } > 0)
    }
}
