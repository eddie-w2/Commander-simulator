package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.sim.Coverage
import com.eddiew.commandersim.sim.Damage
import com.eddiew.commandersim.sim.Draw
import com.eddiew.commandersim.sim.GainLife
import com.eddiew.commandersim.sim.Kw
import com.eddiew.commandersim.sim.LoseLife
import com.eddiew.commandersim.sim.MakeTokens
import com.eddiew.commandersim.sim.ManaColor
import com.eddiew.commandersim.sim.ManaCost
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimCard
import com.eddiew.commandersim.sim.TokenKind
import com.eddiew.commandersim.sim.Trigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickModelTest {
    private fun card(name: String, type: String, cost: String?, oracle: String, p: String? = null, t: String? = null,
                     kw: List<String> = emptyList(), produces: String? = null, q: Int = 1) =
        DeckCard(name, q, typeLine = type, manaValue = ManaCost.parse(cost).total.toDouble(), manaCost = cost, oracle = oracle,
            power = p, toughness = t, keywords = kw, produces = produces, colors = "WB")

    private val merry = card("Merry, Warden of Isengard", "Legendary Creature — Halfling Advisor", "{1}{W}{B}",
        "Partner with Pippin, Warden of Isengard (When this creature enters, target player may put Pippin into their hand from their library, then shuffle.)\n" +
            "Whenever one or more artifacts you control enter, create a 1/1 white Soldier creature token with lifelink. This ability triggers only once each turn.",
        p = "1", t = "4", kw = listOf("Partner with"))

    @Test
    fun `merry makes soldier tokens when artifacts enter`() {
        val c = SimCard.from(merry, ManaColor.W or ManaColor.B, commander = true)
        assertEquals(1, c.power)
        assertEquals(4, c.toughness)
        val a = c.abilities.single()
        assertEquals(Trigger.ARTIFACT_ETB, a.trigger)
        assertTrue(a.oncePerTurn)
        val t = a.effects.single() as MakeTokens
        assertEquals(TokenKind.CREATURE, t.kind)
        assertEquals("Soldier", t.name)
        assertEquals(1, t.power)
        assertEquals(1, t.toughness)
        assertTrue(t.keywords and Kw.LIFELINK != 0)
        assertEquals(Coverage.Status.FULL, c.coverage.status)
        val token = SimCard.token(t, c)
        assertTrue(token.isToken && !token.isCommander)
    }

    @Test
    fun `common triggers and activations are read`() {
        fun sim(type: String, cost: String, oracle: String, name: String = "X") = SimCard.from(card(name, type, cost, oracle), ManaColor.ALL)
        sim("Legendary Creature — Dryad", "{2}{G}{U}", "Landfall — Whenever a land you control enters, you gain 1 life and draw a card.", "Tatyova, Benthic Druid").abilities.single().let {
            assertEquals(Trigger.LANDFALL, it.trigger)
            assertTrue(it.effects.contains(GainLife(1)) && it.effects.contains(Draw(1)))
        }
        sim("Creature — Vampire", "{1}{B}", "Whenever Blood Artist or another creature dies, target player loses 1 life and you gain 1 life.", "Blood Artist").abilities.single().let {
            assertEquals(Trigger.DIES, it.trigger)
            assertTrue(it.effects.contains(LoseLife(1, each = false)))
        }
        sim("Enchantment", "{2}{U}", "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}.").abilities.single().let {
            assertEquals(Trigger.UPKEEP, it.trigger)
        }
        sim("Creature — Goblin", "{1}{R}", "Whenever you cast a noncreature spell, create a Treasure token.").abilities.single().let {
            assertEquals(Trigger.CAST_NONCREATURE, it.trigger)
            assertEquals(TokenKind.TREASURE, (it.effects.single() as MakeTokens).kind)
        }
        sim("Creature — Zombie", "{3}{B}{B}", "When Gray Merchant enters, each opponent loses 2 life.", "Gray Merchant").abilities.single().let {
            assertEquals(Trigger.ETB, it.trigger)
        }
        sim("Artifact", "{3}", "{2}, {T}: Create a 1/1 white Spirit creature token with flying.").activated.single().let {
            assertTrue(it.tap)
            assertEquals(2, it.cost.total)
            assertTrue((it.effects.single() as MakeTokens).keywords and Kw.FLYING != 0)
        }
        sim("Sorcery", "{3}{R}", "Lightning deals 3 damage to each opponent.", "Lightning").abilities.single().let {
            assertEquals(Trigger.CAST, it.trigger)
            assertEquals(Damage(3, each = true), it.effects.single())
        }
        val lord = SimCard.from(card("Elvish Archdruid", "Creature — Elf Druid", "{1}{G}{G}", "Other Elf creatures you control get +1/+1.", "2", "2"), ManaColor.G)
        assertEquals("elf", lord.lordType)
        assertEquals(1, lord.anthem)
    }

    @Test
    fun `tokens never deal commander damage and are reported as their own source`() = runTest {
        val rocks = listOf(
            card("Sol Ring", "Artifact", "{1}", "{T}: Add {C}{C}.", produces = "C"),
            card("Mind Stone", "Artifact", "{2}", "{T}: Add {C}.", produces = "C"),
            card("Fellwar Stone", "Artifact", "{2}", "{T}: Add one mana of any color.", produces = "WB"),
            card("Wayfarer's Bauble", "Artifact", "{1}", "Draw a card.", q = 10),
        )
        fun deck(id: String) = Deck(id, "Deck $id", listOf(merry.copy(colors = "WB")),
            listOf(DeckCard("Plains", 18, typeLine = "Basic Land — Plains"), DeckCard("Swamp", 18, typeLine = "Basic Land — Swamp")) +
                rocks + List(30) { i -> card("Knight $i", "Creature — Knight", "{1}{W}", "", p = "2", t = "2") }, 0L)
        val decks = listOf(deck("a"), deck("b"), deck("c"), deck("d"))
        val r = SampleSimulator(seed = 4).run(SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 100), decks) {}
        for (d in r.decks) {
            // Merry is a 1/4: commander damage can only be what Merry herself dealt in combat.
            val merrySelf = d.card("Merry, Warden of Isengard")!!.combatDamagePerGame
            assertTrue("commander damage ${d.damage.avgCommanderDamage} vs Merry's own $merrySelf", d.damage.avgCommanderDamage <= merrySelf + 1e-9)
            assertTrue(d.damage.sources.any { it.card == "Soldier token (Merry, Warden of Isengard)" })
            assertTrue("win rate ${d.outcome.winRate}", d.outcome.winRate < 0.6)
        }
    }
}
