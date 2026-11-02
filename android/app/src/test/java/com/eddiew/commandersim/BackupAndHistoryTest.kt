package com.eddiew.commandersim

import com.eddiew.commandersim.data.BackupCodec
import com.eddiew.commandersim.data.BackupFile
import com.eddiew.commandersim.data.DeckRepository
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupAndHistoryTest {
    private fun deck(id: String, vararg cards: String) =
        Deck(id, "Deck $id", listOf(DeckCard("Cmdr $id")), cards.map { DeckCard(it) }, 0L, owned = id == "a")

    @Test
    fun `backup round trip`() {
        val b = BackupFile(appVersion = "0.1.02", exportedAt = 1L, decks = listOf(deck("a", "Sol Ring"), deck("b", "Opt")),
            settings = SimSettings(pool = setOf("a")))
        val back = BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(b.decks, back.decks)
        assertEquals(setOf("a"), back.settings?.pool)
        assertTrue(back.decks.first { it.id == "a" }.owned)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage is rejected`() {
        BackupCodec.decode("{\"hello\": 1}")
    }

    @Test
    fun `merge keeps local decks and versions overwritten ones`() {
        val local = listOf(deck("a", "Sol Ring"), deck("c", "Brainstorm"))
        val backup = BackupFile(appVersion = "x", exportedAt = 1L, decks = listOf(deck("a", "Mana Crypt"), deck("b", "Opt")))
        val m = BackupCodec.merge(local, emptyMap(), backup, replace = false, now = 5L)
        assertEquals(setOf("a", "b", "c"), m.decks.map { it.id }.toSet())
        assertEquals(1, m.added)
        assertEquals(1, m.updated)
        assertEquals("Sol Ring", m.history["a"]!!.single().cards.single().name)
        val r = BackupCodec.merge(local, emptyMap(), backup, replace = true)
        assertEquals(setOf("a", "b"), r.decks.map { it.id }.toSet())
    }

    @Test
    fun `history snapshots survive reload and restore`() = runTest {
        val dir = Files.createTempDirectory("cs").toFile()
        val repo = DeckRepository(dir)
        val d = deck("a", "Sol Ring")
        repo.upsert(d)
        repo.snapshot(d, "Before sync", at = 10L)
        repo.upsert(d.copy(cards = listOf(DeckCard("Mana Crypt"))))
        val reloaded = DeckRepository(dir).apply { load() }
        assertEquals("Mana Crypt", reloaded.decks.value.single().cards.single().name)
        val v = reloaded.history.value["a"]!!.single()
        assertEquals("Before sync", v.reason)
        assertEquals("Sol Ring", v.asDeck(reloaded.decks.value.single()).cards.single().name)
        repeat(DeckRepository.MAX_VERSIONS + 5) { reloaded.snapshot(d, "v$it") }
        assertEquals(DeckRepository.MAX_VERSIONS, reloaded.history.value["a"]!!.size)
        reloaded.delete("a")
        assertTrue(reloaded.history.value["a"] == null)
    }
}
