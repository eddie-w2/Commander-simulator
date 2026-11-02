package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.sim.ManaBase
import com.eddiew.commandersim.sim.Category
import com.eddiew.commandersim.sim.ManaColor
import com.eddiew.commandersim.sim.ManaCost
import com.eddiew.commandersim.sim.Role
import com.eddiew.commandersim.sim.SimCard
import com.eddiew.commandersim.sim.payCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardModelTest {
    private val gu = ManaColor.G or ManaColor.U

    private fun card(name: String, type: String, cost: String?, oracle: String = "", produces: String? = null, p: String? = null, t: String? = null, kw: List<String> = emptyList()) =
        DeckCard(name, typeLine = type, manaValue = ManaCost.parse(cost).total.toDouble(), manaCost = cost, produces = produces,
            oracle = oracle, power = p, toughness = t, keywords = kw)

    @Test
    fun `mana costs parse`() {
        assertEquals(ManaCost(listOf(ManaColor.G, ManaColor.G), 2), ManaCost.parse("{2}{G}{G}"))
        val odd = ManaCost.parse("{X}{W/U}{2/B}{G/P}{C}")
        assertEquals(listOf(ManaColor.W or ManaColor.U, ManaColor.B, ManaColor.C), odd.pips)
        assertEquals(0, odd.generic)
        assertEquals("front face only", ManaCost(listOf(ManaColor.R), 1), ManaCost.parse("{1}{R} // {3}{U}"))
        assertEquals("no cost falls back to mana value", ManaCost(emptyList(), 3), ManaCost.parse(null, 3))
    }

    @Test
    fun `costs are paid from the right colours`() {
        val pool = mutableListOf(ManaColor.G, ManaColor.U, ManaColor.G or ManaColor.U)
        assertTrue(payCost(ManaCost.parse("{1}{U}{U}"), pool, commit = false))
        assertFalse(payCost(ManaCost.parse("{U}{U}{U}"), pool, commit = false))
        assertFalse(payCost(ManaCost.parse("{R}"), pool, commit = false))
        assertTrue(payCost(ManaCost.parse("{G}{U}"), pool, commit = true))
        assertEquals("the dual land is kept for last", 1, pool.size)
        assertEquals(ManaColor.G or ManaColor.U, pool.single())
    }

    @Test
    fun `lands and mana sources`() {
        val forest = SimCard.from(DeckCard("Forest", typeLine = "Basic Land — Forest"), gu)
        assertTrue(forest.isLand && forest.isManaSource)
        assertEquals(ManaColor.G, forest.produces)
        assertFalse(forest.entersTapped)

        val tapland = SimCard.from(card("Thornwood Falls", "Land", null, "Thornwood Falls enters tapped.\nWhen it enters, you gain 1 life.\n{T}: Add {G} or {U}.", "GU"), gu)
        assertTrue(tapland.entersTapped)
        assertEquals(gu, tapland.produces)

        val shock = SimCard.from(card("Breeding Pool", "Land — Forest Island", null, "As Breeding Pool enters, you may pay 2 life. If you don't, it enters tapped.", "GU"), gu)
        assertFalse("shocklands can enter untapped", shock.entersTapped)

        val fetch = SimCard.from(card("Evolving Wilds", "Land", null, "{T}, Sacrifice Evolving Wilds: Search your library for a basic land card, put it onto the battlefield tapped, then shuffle."), gu)
        assertEquals("fetches find the deck's colours", gu, fetch.produces)
        assertTrue(fetch.entersTapped)

        val tower = SimCard.from(card("Command Tower", "Land", null, "{T}: Add one mana of any color in your commander's color identity.", "WUBRG"), gu)
        assertEquals(gu, tower.produces)

        val sol = SimCard.from(card("Sol Ring", "Artifact", "{1}", "{T}: Add {C}{C}.", "C"), gu)
        assertTrue(Role.ROCK in sol.roles)
        assertEquals(ManaColor.C, sol.produces)

        val elf = SimCard.from(card("Llanowar Elves", "Creature — Elf Druid", "{G}", "{T}: Add {G}.", "G", "1", "1"), gu)
        assertTrue(Role.DORK in elf.roles && elf.isCreature)
    }

    @Test
    fun `card roles come from rules text`() {
        fun roles(type: String, cost: String, oracle: String) = SimCard.from(card("X", type, cost, oracle), gu).roles
        assertTrue(Role.RAMP_LAND in roles("Sorcery", "{2}{G}", "Search your library for up to two basic land cards, reveal those cards, put one onto the battlefield tapped and the other into your hand, then shuffle."))
        assertTrue(Role.WIPE in roles("Sorcery", "{2}{W}{W}", "Destroy all creatures. They can't be regenerated."))
        assertTrue(Role.REMOVAL in roles("Instant", "{W}", "Exile target creature. Its controller gains life equal to its power."))
        assertTrue(Role.COUNTER in roles("Instant", "{U}{U}", "Counter target spell."))
        assertTrue(Role.DRAW in roles("Instant", "{2}{U}", "Draw three cards."))
        assertTrue(Role.ENGINE in roles("Enchantment", "{2}{U}", "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}."))
        assertTrue(Role.TUTOR in roles("Sorcery", "{1}{B}", "Search your library for a card, put that card into your hand, then shuffle. You lose 2 life."))
        assertTrue(Role.DRAIN in roles("Creature — Zombie", "{3}{B}{B}", "When this enters, each opponent loses 2 life and you gain 2 life."))
        assertTrue(Role.TOKENS in roles("Sorcery", "{2}{W}", "Create two 1/1 white Soldier creature tokens."))
        val tagged = SimCard.from(card("Y", "Instant", "{1}{G}", "Something unusual.").copy(tags = listOf("removal")), gu)
        assertTrue("Scryfall tags add roles", Role.REMOVAL in tagged.roles)
    }

    @Test
    fun `creature stats and keywords`() {
        val c = SimCard.from(card("Serra Angel", "Creature — Angel", "{3}{W}{W}", "Flying, vigilance", null, "4", "4", listOf("Flying", "Vigilance")), ManaColor.W)
        assertEquals(4, c.power)
        assertEquals(4, c.toughness)
        assertTrue(c.flying)
        val anthem = SimCard.from(card("Glorious Anthem", "Enchantment", "{1}{W}{W}", "Creatures you control get +1/+1."), ManaColor.W)
        assertEquals(1, anthem.anthem)
    }

    @Test
    fun `mana base compares pips with sources`() {
        val cards = listOf(
            DeckCard("Forest", 20, typeLine = "Basic Land — Forest"),
            DeckCard("Island", 5, typeLine = "Basic Land — Island"),
            card("Counterspell", "Instant", "{U}{U}", "Counter target spell.").copy(quantity = 10),
            card("Bear", "Creature — Bear", "{1}{G}", "", null, "2", "2").copy(quantity = 10),
        )
        val deck = Deck("d", "Test", listOf(card("Cmd", "Legendary Creature — Elf", "{G}{U}", "", null, "2", "2").copy(colors = "GU")), cards, 0L)
        val base = ManaBase.analyze(deck)
        val blue = base.colors.first { it.color == "U" }
        val green = base.colors.first { it.color == "G" }
        assertEquals(21, blue.pips)
        assertEquals(5, blue.sources)
        assertEquals(2, blue.heaviest)
        assertEquals(11, green.pips)
        assertEquals(25, base.lands)
        assertTrue("warns about blue", base.warnings.any { it.startsWith("Blue") })
    }

    @Test
    fun `card categories`() {
        fun cats(type: String, cost: String, oracle: String, produces: String? = null) =
            com.eddiew.commandersim.sim.CardCategories.of(card("X", type, cost, oracle, produces), gu)
        assertEquals(setOf(Category.RAMP), cats("Artifact", "{1}", "{T}: Add {C}{C}.", "C"))
        assertEquals(setOf(Category.PROTECTION), cats("Instant", "{G}", "Target creature you control gains hexproof and indestructible until end of turn."))
        assertEquals(setOf(Category.PROTECTION), cats("Instant", "{2}{W}", "Creatures you control gain indestructible until end of turn."))
        assertEquals(setOf(Category.RECURSION), cats("Sorcery", "{1}{G}", "Return target permanent card from your graveyard to your hand."))
        assertEquals("the token goes to the opponent", setOf(Category.REMOVAL), cats("Instant", "{2}{G}", "Destroy target permanent. Its controller creates a 3/3 green Beast creature token."))
        assertTrue(Category.WIPE in cats("Sorcery", "{2}{W}{W}", "Destroy all creatures. They can't be regenerated."))
        assertTrue("lands have no category", com.eddiew.commandersim.sim.CardCategories.of(DeckCard("Forest", typeLine = "Basic Land — Forest"), gu).isEmpty())
    }

    @Test
    fun `collection colour filter`() {
        val deck = Deck("d", "Test", emptyList(), listOf(
            DeckCard("Elf", colors = "G"), DeckCard("Kraken", colors = "U"), DeckCard("Simic", colors = "GU"),
            DeckCard("Ring", colors = ""), DeckCard("Unknown"),
        ), 0L)
        fun names(f: String) = com.eddiew.commandersim.data.CollectionStats.filterColors(listOf(deck), f).single().cards.map { it.name }
        assertEquals(listOf("Elf", "Simic"), names("G"))
        assertEquals(listOf("Simic"), names("GU"))
        assertEquals(listOf("Ring"), names("C"))
        assertEquals(5, names("").size)
    }
}

