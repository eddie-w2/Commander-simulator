package com.eddiew.commandersim

import com.eddiew.commandersim.data.CollectionStats
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionStatsTest {
    private fun c(name: String, colors: String?, type: String = "Instant", qty: Int = 1) = DeckCard(name, qty, typeLine = type, colors = colors)

    private val a = Deck("a", "A", listOf(c("Lathril", "BG", "Legendary Creature")), listOf(
        c("Sol Ring", "", "Artifact"), c("Swamp", "B", "Basic Land — Swamp", 10), c("Cultivate", "G", "Sorcery"),
        c("Putrefy", "BG"), c("Command Tower", "", "Land"),
    ), 0L, owned = true)
    private val b = Deck("b", "B", listOf(c("Krenko", "R", "Legendary Creature")), listOf(
        c("Sol Ring", "", "Artifact"), c("Swamp", "B", "Basic Land — Swamp", 5), c("Lightning Bolt", "R"), c("Command Tower", "", "Land"),
    ), 0L, owned = true)
    private val d = Deck("d", "D", listOf(c("Atraxa", "WUBG", "Legendary Creature")), listOf(
        c("Sol Ring", "", "Artifact"), c("Cultivate", "G", "Sorcery"),
    ), 0L, owned = true)

    @Test
    fun `shared cards exclude basics and are sorted by count`() {
        val shared = CollectionStats.sharedCards(listOf(a, b, d))
        assertEquals("Sol Ring", shared.first().name)
        assertEquals(3, shared.first().count)
        assertEquals(setOf("Sol Ring", "Cultivate", "Command Tower"), shared.map { it.name }.toSet())
        // Lands toggle
        assertEquals(setOf("Sol Ring", "Cultivate"), CollectionStats.sharedCards(listOf(a, b, d), includeLands = false).map { it.name }.toSet())
    }

    @Test
    fun `overlap matrix counts distinct non-basic cards`() {
        val m = CollectionStats.overlap(listOf(a, b))
        assertEquals(2, m[0][1]) // Sol Ring, Command Tower
        assertEquals(m[0][1], m[1][0])
    }

    @Test
    fun `colour counts per deck`() {
        val col = CollectionStats.colors(a) // lands excluded
        assertEquals(2, col['B']) // Lathril, Putrefy
        assertEquals(3, col['G']) // Lathril, Cultivate, Putrefy
        assertEquals(1, col['C']) // Sol Ring
        assertEquals(1, col.mono)
        assertEquals(2, col.multi)
        assertEquals(1, col.colorless)
    }

    @Test
    fun `cumulative vs unique colours`() {
        val all = CollectionStats.cumulativeColors(listOf(a, b, d))
        val unique = CollectionStats.uniqueColors(listOf(a, b, d))
        assertEquals(3, all['C']) // Sol Ring in three decks
        assertEquals(1, unique['C'])
    }

    @Test
    fun `summary counts extra copies`() {
        val s = CollectionStats.summary(listOf(a, b, d))
        assertEquals(3, s.decks)
        // Sol Ring ×3 (2 extra), Cultivate ×2 (1), Command Tower ×2 (1)
        assertEquals(4, s.copiesNeeded)
    }
}
