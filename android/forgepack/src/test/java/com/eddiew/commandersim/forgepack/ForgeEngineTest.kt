package com.eddiew.commandersim.forgepack

import com.eddiew.commandersim.sim.BridgeDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plays a real Forge game. Runs only when FORGE_RES points at an unpacked forge-res bundle
 * (the nightly Forge pack job sets it as a smoke test); skipped otherwise.
 */
class ForgeEngineTest {
    private val res = System.getenv("FORGE_RES")?.let { File(it) }

    private fun deck(id: String, commander: String, land: String, vararg cards: Pair<String, Int>): BridgeDeck {
        val lands = 99 - cards.sumOf { it.second }
        return BridgeDeck(id, "Deck $id", listOf(commander), cards.toList() + (land to lands))
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
        val bridge = Bridge()
        val missing = bridge.prepare(res!!, Files.createTempDirectory("forge-work").toFile(), decks) { println(it) }
        assertTrue("all test cards known: $missing", missing.isEmpty())
        val start = System.currentTimeMillis()
        val r = bridge.play(decks.map { it.id }, 1L, 18, 120_000) {}
        println("Forge game: rounds=${r.rounds} winner=${r.winnerSeat} cause=${r.winCondition} adjudicated=${r.adjudicated} in ${System.currentTimeMillis() - start} ms")
        println("Diagnostics: ${bridge.lastDiagnostics()}")
        r.tracks.forEachIndexed { i, t ->
            println("  seat $i place=${t.place} turns=${t.turnsTaken} dmg=${t.dmgPlayers} lands=${t.landsPlayed} casts=${t.cards.values.sumOf { it.casts }}")
        }
        assertEquals(4, r.tracks.size)
        assertTrue("game lasted some turns", r.rounds >= 2)
        assertTrue("spells were cast", r.tracks.sumOf { t -> t.cards.values.sumOf { it.casts } } > r.tracks.sumOf { it.landsPlayed })
        assertTrue("damage was dealt", r.tracks.sumOf { it.dmgPlayers } > 0)
    }
}
