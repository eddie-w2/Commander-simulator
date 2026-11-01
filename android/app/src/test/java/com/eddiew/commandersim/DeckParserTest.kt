package com.eddiew.commandersim

import com.eddiew.commandersim.data.DeckParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckParserTest {

    @Test
    fun `plain list with commander header`() {
        val r = DeckParser.parse(
            """
            Commander
            1 Atraxa, Praetors' Voice

            Deck
            1 Sol Ring
            1 Arcane Signet
            10 Forest
            """.trimIndent(),
        )
        assertEquals(listOf("Atraxa, Praetors' Voice"), r.commanders.map { it.name })
        assertEquals(3, r.cards.size)
        assertEquals(13, r.total)
    }

    @Test
    fun `moxfield style set codes and foil markers are stripped`() {
        val (card, cmd) = DeckParser.parseCardLine("1 Sol Ring (CMR) 472 *F*")!!
        assertEquals("Sol Ring", card.name)
        assertEquals("CMR", card.setCode)
        assertEquals("472", card.collectorNumber)
        assertTrue(!cmd)
    }

    @Test
    fun `archidekt commander tag and x quantity`() {
        val (card, cmd) = DeckParser.parseCardLine("1x Kenrith, the Returned King (eld) 303 [Commander{top}]")!!
        assertEquals("Kenrith, the Returned King", card.name)
        assertTrue(cmd)
        val (ramp, isCmd) = DeckParser.parseCardLine("1x Cultivate (m21) 177 [Ramp]")!!
        assertEquals("Cultivate", ramp.name)
        assertTrue(!isCmd)
    }

    @Test
    fun `cmdr marker`() {
        val r = DeckParser.parse("1 Krenko, Mob Boss *CMDR*\n1 Goblin Matron")
        assertEquals("Krenko, Mob Boss", r.commanders.single().name)
        assertEquals("Goblin Matron", r.cards.single().name)
    }

    @Test
    fun `sideboard and maybeboard are skipped, category headers are not cards`() {
        val r = DeckParser.parse(
            """
            Creatures (2)
            1 Llanowar Elves
            1 Elvish Mystic
            Sideboard:
            1 Naturalize
            Maybeboard (1)
            1 Heroic Intervention
            """.trimIndent(),
        )
        assertEquals(listOf("Llanowar Elves", "Elvish Mystic"), r.cards.map { it.name })
        assertEquals(2, r.ignored.size)
    }

    @Test
    fun `duplicates merge and singleton warning ignores basics`() {
        val r = DeckParser.parse("1 Sol Ring\n1 Sol Ring\n20 Island\n1 Atraxa, Praetors' Voice *CMDR*")
        assertEquals(2, r.cards.first { it.name == "Sol Ring" }.quantity)
        assertTrue(r.warnings.any { "Sol Ring ×2" in it })
        assertTrue(r.warnings.none { "Island" in it && "copy" in it })
    }

    @Test
    fun `split cards are normalised`() {
        val (card, _) = DeckParser.parseCardLine("1 Fire/Ice")!!
        assertEquals("Fire // Ice", card.name)
    }

    @Test
    fun `no commander gives a warning`() {
        val r = DeckParser.parse("1 Sol Ring")
        assertTrue(r.warnings.any { it.startsWith("No commander") })
        assertNull(r.commanders.firstOrNull())
    }
}
