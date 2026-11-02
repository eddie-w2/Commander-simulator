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

    private fun sim(name: String, type: String, cost: String?, oracle: String, produces: String? = null, p: String? = null, t: String? = null,
                    kw: List<String> = emptyList()) =
        SimCard.from(card(name, type, cost, oracle, p, t, kw, produces), ManaColor.ALL)

    @Test
    fun `mana sources make the right amount`() {
        assertEquals(2, sim("Sol Ring", "Artifact", "{1}", "{T}: Add {C}{C}.", "C").manaAmount)
        assertEquals(2, sim("Ancient Tomb", "Land", null, "{T}: Add {C}{C}. Ancient Tomb deals 2 damage to you.", "C").manaAmount)
        assertEquals(1, sim("Arcane Signet", "Artifact", "{2}", "{T}: Add one mana of any color in your commander's color identity.", "WUBRG").manaAmount)
        val cradle = sim("Gaea's Cradle", "Legendary Land", null, "{T}: Add {G} for each creature you control.", "G")
        assertEquals(com.eddiew.commandersim.sim.ScaleKind.CREATURES, cradle.manaScale?.kind)
        val coffers = sim("Cabal Coffers", "Land", null, "{2}, {T}: Add {B} for each Swamp you control.", "B")
        assertEquals(2, coffers.manaActivation)
        assertEquals(com.eddiew.commandersim.sim.ScaleKind.LANDS_OF_COLOR, coffers.manaScale?.kind)
    }

    @Test
    fun `tutors know what they find and where it goes`() {
        val demonic = sim("Demonic Tutor", "Sorcery", "{1}{B}", "Search your library for a card, put that card into your hand, then shuffle.")
        assertEquals(com.eddiew.commandersim.sim.TutorSpec(null, null, com.eddiew.commandersim.sim.TutorDest.HAND), demonic.tutor)
        val vampiric = sim("Vampiric Tutor", "Instant", "{B}", "Search your library for a card, then shuffle and put that card on top. You lose 2 life.")
        assertEquals(com.eddiew.commandersim.sim.TutorDest.TOP, vampiric.tutor!!.dest)
        val green = sim("Green Sun's Zenith", "Sorcery", "{X}{G}", "Search your library for a green creature card with mana value X or less, put it onto the battlefield, then shuffle.")
        assertEquals(setOf(com.eddiew.commandersim.data.model.CardType.CREATURE), green.tutor!!.types)
        assertEquals(com.eddiew.commandersim.sim.TutorDest.BATTLEFIELD, green.tutor!!.dest)
        val cultivate = sim("Cultivate", "Sorcery", "{2}{G}", "Search your library for up to two basic land cards, reveal those cards, put one onto the battlefield tapped and the other into your hand, then shuffle.")
        assertEquals("land searches are ramp, not tutors", null, cultivate.tutor)
    }

    @Test
    fun `win conditions and other effects are read`() {
        val infect = sim("Blighted Agent", "Creature — Phyrexian Human Rogue", "{1}{U}", "Infect\nBlighted Agent can't be blocked.", p = "1", t = "1", kw = listOf("Infect"))
        assertTrue(infect.has(Kw.INFECT) && infect.unblockable)
        assertEquals(2, sim("Toxic Rat", "Creature — Rat", "{1}{B}", "Toxic 2", p = "1", t = "1").toxic)
        sim("Mesmeric Orb", "Artifact", "{2}", "Whenever a permanent becomes untapped, that permanent's controller mills a card.")
        sim("Glimpse the Cosmos", "Sorcery", "{3}{U}", "Target player mills four cards.").abilities.single().let {
            assertEquals(com.eddiew.commandersim.sim.Mill(4, com.eddiew.commandersim.sim.MillTarget.OPPONENT), it.effects.single())
        }
        sim("Time Warp", "Sorcery", "{3}{U}{U}", "Target player takes an extra turn after this one.").abilities.single().let {
            assertEquals(com.eddiew.commandersim.sim.ExtraTurn, it.effects.single())
        }
        sim("Felidar Sovereign", "Creature — Cat Beast", "{4}{W}{W}", "Vigilance, lifelink\nAt the beginning of your upkeep, if you have 40 or more life, you win the game.", p = "4", t = "6")
            .abilities.single().let { assertEquals(com.eddiew.commandersim.sim.WinGame(com.eddiew.commandersim.sim.WinCondition.LIFE, 40), it.effects.single()) }
        val gray = sim("Gray Merchant of Asphodel", "Creature — Zombie", "{3}{B}{B}",
            "When Gray Merchant of Asphodel enters, each opponent loses X life, where X is your devotion to black. You gain life equal to the life lost this way.", p = "2", t = "4")
        assertEquals(com.eddiew.commandersim.sim.ScaleKind.DEVOTION, (gray.abilities.single().effects.first() as LoseLife).scale?.kind)
    }

    @Test
    fun `modal spells use one mode, X and other card types`() {
        val modal = sim("Abzan Charm", "Instant", "{W}{B}{G}",
            "Choose one —\n• Exile target creature with power 3 or greater.\n• You draw two cards and you lose 2 life.\n• Distribute two +1/+1 counters among one or two target creatures.")
        assertEquals("only one mode's effects", 1, modal.abilities.size)
        val fireball = sim("Crackle", "Sorcery", "{X}{R}", "Crackle deals X damage to each opponent.")
        assertEquals(1, fireball.cost.x)
        assertEquals(Damage(com.eddiew.commandersim.sim.XN, each = true), fireball.abilities.single().effects.single())
        val walker = sim("Test Walker", "Legendary Planeswalker — Test", "{2}{G}", "+1: Create a 1/1 green Elf Warrior creature token.\n−3: Draw three cards.")
        assertEquals(listOf(1, -3), walker.loyalty.map { it.delta })
        val saga = sim("Test Saga", "Enchantment — Saga", "{2}{W}", "(As this Saga enters and after your draw step, add a lore counter.)\nI — Create a 1/1 white Soldier creature token.\nII, III — You gain 3 life.")
        assertEquals(3, saga.chapters.size)
        val sword = sim("Test Sword", "Artifact — Equipment", "{3}", "Equipped creature gets +2/+2 and has flying.\nEquip {2}")
        assertEquals(2, sword.equipCost)
        assertEquals(2, sword.buff)
        assertTrue(sword.grants and Kw.FLYING != 0)
        val reducer = sim("Goblin Electromancer", "Creature — Goblin Wizard", "{U}{R}", "Instant and sorcery spells you cast cost {1} less to cast.", p = "2", t = "2")
        assertEquals(1, reducer.reduceBy)
        assertEquals(com.eddiew.commandersim.sim.RemovalKind.FIGHT,
            sim("Prey Upon", "Sorcery", "{G}", "Target creature you control fights target creature you don't control.").removalKind)
        assertEquals(com.eddiew.commandersim.sim.RemovalKind.MINUS,
            sim("Dismember", "Instant", "{1}{B/P}{B/P}", "Target creature gets -5/-5 until end of turn.").removalKind)
        assertEquals(3, sim("Giant Growth", "Instant", "{G}", "Target creature gets +3/+3 until end of turn.").pump)
        val clone = sim("Clone", "Creature — Shapeshifter", "{3}{U}", "You may have Clone enter the battlefield as a copy of any creature on the battlefield.", p = "0", t = "0")
        assertEquals(com.eddiew.commandersim.sim.CopyCreature(token = false, ownOnly = false), clone.abilities.single().effects.single())
        assertEquals(Trigger.ETB, clone.abilities.single().trigger)
        val entomb = sim("Entomb", "Instant", "{B}", "Search your library for a card, put that card into your graveyard, then shuffle.")
        assertEquals(com.eddiew.commandersim.sim.TutorDest.GRAVEYARD, entomb.tutor!!.dest)
        val gray = sim("Gray Merchant of Asphodel", "Creature — Zombie", "{3}{B}{B}",
            "When Gray Merchant of Asphodel enters, each opponent loses X life, where X is your devotion to black. You gain life equal to the life lost this way.", p = "2", t = "4")
        assertTrue((gray.abilities.single().effects.first() as LoseLife).gainBack)
    }

    @Test
    fun `lopsided results are flagged`() = runTest {
        val strong = { id: String -> Deck(id, "Strong $id", listOf(card("Cmd $id", "Legendary Creature — Giant", "{1}{W}", "", p = "9", t = "9").copy(colors = "W")),
            listOf(DeckCard("Plains", 38, typeLine = "Basic Land — Plains", colors = "W")) +
                List(61) { i -> card("Giant $id $i", "Creature — Giant", "{1}{W}", "", p = "8", t = "8", kw = listOf("Trample")).copy(colors = "W") }, 0L) }
        val weak = { id: String -> Deck(id, "Weak $id", listOf(card("Cmd $id", "Legendary Creature — Bug", "{5}{W}", "", p = "1", t = "1").copy(colors = "W")),
            listOf(DeckCard("Plains", 38, typeLine = "Basic Land — Plains", colors = "W")) +
                List(61) { i -> card("Bug $id $i", "Creature — Bug", "{5}{W}", "", p = "1", t = "1").copy(colors = "W") }, 0L) }
        val decks = listOf(strong("s"), weak("a"), weak("b"), weak("c"))
        val r = SampleSimulator(seed = 2).run(SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 60), decks) {}
        assertTrue(r.notes.joinToString(), r.notes.any { it.startsWith("Check:") && "Strong s" in it })
    }

    @Test
    fun `a deck that assembles a known combo wins with it`() = runTest {
        val oracle = card("Thassa's Oracle", "Creature — Merfolk Wizard", "{U}{U}",
            "When Thassa's Oracle enters, look at the top X cards of your library, where X is your devotion to blue. Put up to one of them on top of your library and the rest on the bottom of your library in a random order. If X is greater than or equal to the number of cards in your library, you win the game.",
            p = "1", t = "3").copy(colors = "U")
        val consult = card("Demonic Consultation", "Instant", "{B}", "Choose a card name. Exile the top six cards of your library, then reveal cards from the top of your library until you reveal a card with the chosen name. Put that card into your hand and exile all other cards revealed this way.")
            .copy(colors = "B")
        val tutor = card("Demonic Tutor", "Sorcery", "{1}{B}", "Search your library for a card, put that card into your hand, then shuffle.", q = 4).copy(colors = "B")
        fun deck(id: String, combo: Boolean) = Deck(id, "Deck $id", listOf(card("Cmd $id", "Legendary Creature — Human", "{1}{U}{B}", "", p = "2", t = "2").copy(colors = "UB")),
            listOf(DeckCard("Island", 18, typeLine = "Basic Land — Island", colors = "U"), DeckCard("Swamp", 18, typeLine = "Basic Land — Swamp", colors = "B")) +
                (if (combo) listOf(oracle, consult, tutor) else emptyList()) +
                List(if (combo) 57 else 63) { i -> card("Bear $id $i", "Creature — Bear", "{1}{U}", "", p = "2", t = "2").copy(colors = "U") }, 0L)
        val decks = listOf(deck("combo", true), deck("b", false), deck("c", false), deck("d", false))
        val r = SampleSimulator(seed = 8).run(SimConfig(deckIds = decks.map { it.id }, gamesPerDeck = 200), decks) {}
        val combo = r.deck("combo")!!
        val alt = combo.outcome.winConditions.firstOrNull { it.condition == com.eddiew.commandersim.data.model.EndCondition.ALT_WIN }?.count ?: 0
        // One copy of each piece and four tutors in 99 cards: assembled in a good share of games, and never by the others.
        assertTrue("combo wins: $alt of ${combo.outcome.games} games", alt >= combo.outcome.games / 10)
        for (d in r.decks.filter { it.deckId != "combo" }) {
            assertEquals(0, d.outcome.winConditions.firstOrNull { it.condition == com.eddiew.commandersim.data.model.EndCondition.ALT_WIN }?.count ?: 0)
        }
    }
}

