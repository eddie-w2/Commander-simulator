package com.eddiew.commandersim

import com.eddiew.commandersim.data.DeckHistory
import com.eddiew.commandersim.data.DeckRunStat
import com.eddiew.commandersim.data.RunSummary
import com.eddiew.commandersim.data.VsStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckHistoryTest {
    private fun run(id: String, at: Long, source: String, vararg decks: DeckRunStat) =
        RunSummary(id, at, source, ab = false, games = 10, plannedGames = 10, decks = decks.toList())

    @Test
    fun `cumulative deck record across runs`() {
        val runs = listOf(
            run("r2", 2000, "forge",
                DeckRunStat("a", "Elves", games = 10, wins = 5, avgPlace = 1.8, avgDamage = 60.0, vs = listOf(VsStat("b", "Burn", 10, 5))),
                DeckRunStat("b", "Burn", games = 10, wins = 2, avgPlace = 2.6, avgDamage = 40.0)),
            run("r1", 1000, "quick",
                DeckRunStat("a", "Elves", games = 30, wins = 6, avgPlace = 2.4, avgDamage = 50.0, vs = listOf(VsStat("b", "Burn", 30, 6))),
                DeckRunStat("c", "Control", games = 30, wins = 9, avgPlace = 2.2, avgDamage = 30.0)),
            run("r0", 500, "quick", DeckRunStat("c", "Control", games = 20, wins = 5, avgPlace = 2.5, avgDamage = 20.0)),
        )
        val h = DeckHistory.of(runs, "a")
        assertEquals(listOf("r2", "r1"), h.runs.map { it.first.id })
        assertEquals(40, h.games)
        assertEquals(11, h.wins)
        assertEquals((1.8 * 10 + 2.4 * 30) / 40, h.avgPlace!!, 1e-9)
        assertEquals(10 to 5, h.byEngine["Forge"])
        assertEquals(30 to 6, h.byEngine["Quick model"])
        assertEquals(listOf(0.2, 0.5), h.trend)
        assertEquals(VsStat("b", "Burn", 40, 11), h.vs.single())
        assertTrue(h.ci.first < h.winRate && h.winRate < h.ci.second)
        assertEquals(0, DeckHistory.of(runs, "zzz").games)
    }
}
