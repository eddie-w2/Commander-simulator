package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.ColorLine
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.ManaBaseStats
import com.eddiew.commandersim.data.model.NamedValue
import kotlin.math.roundToInt

/**
 * Static mana base analysis of a deck list: coloured pips vs coloured sources, curve and card roles.
 * Needs the deck's Scryfall data (mana costs, produced mana); works partially without it.
 */
object ManaBase {
    /** Rough source targets by the most pips of one colour a single card needs (99-card decks, casting a bit off curve). */
    fun suggestedSources(heaviest: Int): Int = when {
        heaviest <= 0 -> 0
        heaviest == 1 -> 12
        heaviest == 2 -> 18
        else -> 22
    }

    fun analyze(deck: Deck): ManaBaseStats {
        val deckColors = ManaColor.mask(deck.colorIdentity)
        val main = deck.cards.map { it to SimCard.from(it, deckColors) }
        val cmds = deck.commanders.map { it to SimCard.from(it, deckColors, commander = true) }
        val all = main + cmds

        val pips = IntArray(5)
        val cardsNeeding = IntArray(5)
        val heaviest = IntArray(5)
        val sources = IntArray(5)
        val landSources = IntArray(5)
        var lands = 0; var rocks = 0; var dorks = 0; var rampSpells = 0; var tapped = 0
        val curve = IntArray(8)
        var mvSum = 0; var mvN = 0
        val roleCount = HashMap<String, Int>()

        for ((dc, c) in all) {
            val q = dc.quantity
            if (!c.isLand) {
                val perColor = IntArray(5)
                for (pip in c.cost.pips) {
                    // A hybrid pip is split between its colours.
                    val colors = ManaColor.ORDER.indices.filter { pip and ManaColor.ORDER[it].second != 0 }
                    colors.forEach { perColor[it]++ }
                }
                for (i in 0 until 5) if (perColor[i] > 0) {
                    pips[i] += perColor[i] * q
                    cardsNeeding[i] += q
                    heaviest[i] = maxOf(heaviest[i], perColor[i])
                }
                if (!c.isCommander) {
                    curve[c.mv.coerceIn(0, 7)] += q
                    mvSum += c.mv * q; mvN += q
                }
            }
            if (c.isCommander) continue
            if (c.isManaSource) for (i in 0 until 5) if (c.produces and ManaColor.ORDER[i].second != 0) {
                sources[i] += q
                if (c.isLand) landSources[i] += q
            }
            when {
                c.isLand -> { lands += q; if (c.entersTapped) tapped += q }
                Role.ROCK in c.roles -> rocks += q
                Role.DORK in c.roles -> dorks += q
            }
            if (Role.RAMP_LAND in c.roles) rampSpells += q
            fun role(label: String, has: Boolean) { if (has) roleCount.merge(label, q, Int::plus) }
            role("Ramp", Role.ROCK in c.roles || Role.DORK in c.roles || Role.RAMP_LAND in c.roles)
            role("Card draw", Role.DRAW in c.roles || Role.ENGINE in c.roles)
            role("Removal", Role.REMOVAL in c.roles)
            role("Board wipes", Role.WIPE in c.roles)
            role("Counterspells", Role.COUNTER in c.roles)
            role("Tutors", Role.TUTOR in c.roles)
            role("Burn / drain", Role.BURN in c.roles || Role.DRAIN in c.roles)
            role("Token makers", Role.TOKENS in c.roles)
        }

        val pipTotal = pips.sum().coerceAtLeast(1)
        val sourceTotal = sources.sum().coerceAtLeast(1)
        val colorIdx = ManaColor.ORDER.indices.filter { deckColors and ManaColor.ORDER[it].second != 0 || pips[it] > 0 }
        val lines = colorIdx.map { i ->
            ColorLine(
                color = ManaColor.ORDER[i].first.toString(),
                pips = pips[i], pipShare = pips[i].toDouble() / pipTotal,
                sources = sources[i], landSources = landSources[i], sourceShare = sources[i].toDouble() / sourceTotal,
                cards = cardsNeeding[i], heaviest = heaviest[i], suggested = suggestedSources(heaviest[i]),
            )
        }

        val withData = (deck.cards + deck.commanders).count { it.manaCost != null || it.produces != null || it.oracle != null }
        val complete = withData >= (deck.cards.size + deck.commanders.size) * 0.9
        val warnings = buildList {
            if (!complete) add("Card data is still loading from Scryfall; these numbers are incomplete.")
            for (l in lines) {
                val name = colorName(l.color)
                if (l.pips >= 8 && l.sourceShare < l.pipShare - 0.10)
                    add("$name is ${(l.pipShare * 100).roundToInt()}% of your pips but only ${(l.sourceShare * 100).roundToInt()}% of your coloured sources.")
                if (l.suggested > 0 && l.sources < l.suggested - 2)
                    add("$name has ${l.sources} sources; cards needing ${"{${l.color}}".repeat(l.heaviest.coerceAtMost(3))} usually want about ${l.suggested}.")
                if (l.pips > 0 && l.sources == 0) add("Nothing in the deck makes $name mana.")
            }
            if (lands in 1..32) add("$lands lands is low for Commander (most decks run 35–38, a few less with lots of ramp).")
            if (tapped >= 10) add("$tapped lands enter tapped; expect to fall behind on tempo.")
        }
        return ManaBaseStats(
            colors = lines, lands = lands, rocks = rocks, dorks = dorks, rampSpells = rampSpells, tappedLands = tapped,
            avgManaValue = if (mvN > 0) mvSum.toDouble() / mvN else 0.0, curve = curve.toList(),
            roles = roleCount.entries.sortedByDescending { it.value }.map { NamedValue(it.key, it.value.toDouble()) },
            warnings = warnings, dataComplete = complete,
        )
    }

    fun colorName(letter: String): String = when (letter) {
        "W" -> "White"; "U" -> "Blue"; "B" -> "Black"; "R" -> "Red"; "G" -> "Green"; else -> "Colourless"
    }
}
