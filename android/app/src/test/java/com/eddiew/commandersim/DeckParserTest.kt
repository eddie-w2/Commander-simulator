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

    private fun hundred(commanderFirst: Boolean): String {
        val body = (1..98).joinToString("\n") { "1 Card $it" } + "\n1 Sol Ring"
        return if (commanderFirst) "1 Lathril, Blade of the Elves\n\n$body" else "$body\n\n1 Lathril, Blade of the Elves"
    }

    @Test
    fun `commander listed separately first is inferred (99+1)`() {
        val r = DeckParser.parse(hundred(commanderFirst = true))
        assertEquals("Lathril, Blade of the Elves", r.commanders.single().name)
        assertTrue(r.commanderInferred)
        assertEquals(99, r.cards.sumOf { it.quantity })
    }

    @Test
    fun `commander listed separately last is inferred`() {
        val r = DeckParser.parse(hundred(commanderFirst = false))
        assertEquals("Lathril, Blade of the Elves", r.commanders.single().name)
    }

    @Test
    fun `two partners separated are inferred (98+2)`() {
        val body = (1..98).joinToString("\n") { "1 Card $it" }
        val r = DeckParser.parse("1 Thrasios, Triton Hero\n1 Tymna the Weaver\n\n$body")
        assertEquals(listOf("Thrasios, Triton Hero", "Tymna the Weaver"), r.commanders.map { it.name })
    }

    @Test
    fun `mtgo style sideboard commander`() {
        val body = (1..99).joinToString("\n") { "1 Card $it" }
        val r = DeckParser.parse("$body\n\nSIDEBOARD:\n1 Krenko, Mob Boss")
        assertEquals("Krenko, Mob Boss", r.commanders.single().name)
    }

    @Test
    fun `no blank-line structure leaves commander for detection`() {
        val r = DeckParser.parse((1..100).joinToString("\n") { "1 Card $it" })
        assertTrue(r.commanders.isEmpty())
    }

    @Test
    fun `detects commander by legendary and colour identity`() {
        val cards = listOf(
            com.eddiew.commandersim.data.model.DeckCard("Lathril, Blade of the Elves"),
            com.eddiew.commandersim.data.model.DeckCard("Elvish Mystic"),
            com.eddiew.commandersim.data.model.DeckCard("Rhys the Exiled"),
            com.eddiew.commandersim.data.model.DeckCard("Village Rites"),
        )
        val facts = mapOf(
            "lathril, blade of the elves" to DeckParser.CardFacts("BG", canBeCommander = true, partner = false),
            "elvish mystic" to DeckParser.CardFacts("G", canBeCommander = false, partner = false),
            "rhys the exiled" to DeckParser.CardFacts("G", canBeCommander = true, partner = false),
            "village rites" to DeckParser.CardFacts("B", canBeCommander = false, partner = false),
        )
        // Rhys is legendary but can't cover the black card, so Lathril is the only fit.
        assertEquals(listOf("Lathril, Blade of the Elves"), DeckParser.detectCommander(cards, facts)?.map { it.name })
    }

    @Test
    fun `detects partner pair`() {
        val cards = listOf("Thrasios, Triton Hero", "Tymna the Weaver", "Counterspell", "Swords to Plowshares", "Llanowar Elves", "Dark Ritual")
            .map { com.eddiew.commandersim.data.model.DeckCard(it) }
        val facts = mapOf(
            "thrasios, triton hero" to DeckParser.CardFacts("GU", true, true),
            "tymna the weaver" to DeckParser.CardFacts("WB", true, true),
            "counterspell" to DeckParser.CardFacts("U", false, false),
            "swords to plowshares" to DeckParser.CardFacts("W", false, false),
            "llanowar elves" to DeckParser.CardFacts("G", false, false),
            "dark ritual" to DeckParser.CardFacts("B", false, false),
        )
        assertEquals(setOf("Thrasios, Triton Hero", "Tymna the Weaver"), DeckParser.detectCommander(cards, facts)?.map { it.name }?.toSet())
    }
}
