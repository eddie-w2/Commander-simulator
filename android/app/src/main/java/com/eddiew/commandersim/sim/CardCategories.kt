package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard

/** Deck-building categories a card can fall into. A card can be in several. */
enum class Category(val label: String, val short: String) {
    RAMP("Ramp", "RMP"),
    DRAW("Card draw", "DRW"),
    REMOVAL("Removal", "REM"),
    WIPE("Board wipes", "WIP"),
    COUNTER("Counterspells", "CTR"),
    PROTECTION("Protection", "PRT"),
    TUTOR("Tutors", "TUT"),
    RECURSION("Recursion", "REC"),
    TOKENS("Token makers", "TOK"),
    BURN("Burn / drain", "BRN"),
    LIFEGAIN("Lifegain", "LIF"),
}

/**
 * Automatic card categories, from Scryfall's functional tags and the card's rules text (the same detection the
 * quick model uses, plus protection and recursion). Lands are never categorised.
 */
object CardCategories {
    fun of(card: DeckCard, deckColors: Int, commander: Boolean = false): Set<Category> {
        val sim = SimCard.from(card, deckColors, commander)
        if (sim.isLand) return emptySet()
        val r = sim.roles
        val out = LinkedHashSet<Category>()
        if (Role.ROCK in r || Role.DORK in r || Role.RAMP_LAND in r) out += Category.RAMP
        if (Role.DRAW in r || Role.ENGINE in r) out += Category.DRAW
        if (Role.REMOVAL in r) out += Category.REMOVAL
        if (Role.WIPE in r) out += Category.WIPE
        if (Role.COUNTER in r) out += Category.COUNTER
        if (Role.PROTECTION in r) out += Category.PROTECTION
        if (Role.TUTOR in r) out += Category.TUTOR
        if (Role.RECURSION in r) out += Category.RECURSION
        if (Role.TOKENS in r) out += Category.TOKENS
        if (Role.BURN in r || Role.DRAIN in r) out += Category.BURN
        if (Role.LIFEGAIN in r) out += Category.LIFEGAIN
        return out.sortedBy { it.ordinal }.toSet()
    }

    /** Each category → the deck's cards in it (commanders included), in category order. */
    fun breakdown(deck: Deck): Map<Category, List<DeckCard>> {
        val colors = ManaColor.mask(deck.colorIdentity)
        val out = Category.entries.associateWith { mutableListOf<DeckCard>() }
        deck.commanders.forEach { c -> of(c, colors, commander = true).forEach { out.getValue(it) += c } }
        deck.cards.forEach { c -> of(c, colors).forEach { out.getValue(it) += c } }
        return out
    }

    /** Copies per category in [deck]. */
    fun counts(deck: Deck): Map<Category, Int> = breakdown(deck).mapValues { (_, cards) -> cards.sumOf { it.quantity } }

    /** True when the deck has rules text to categorise from (card data loaded). */
    fun hasData(deck: Deck): Boolean = (deck.commanders + deck.cards).count { it.oracle != null } >= (deck.cards.size + deck.commanders.size) / 2

    /** A widely used Commander deck-building guideline, for reference lines. */
    val guideline: Map<Category, Int> = mapOf(Category.RAMP to 10, Category.DRAW to 10, Category.REMOVAL to 8, Category.WIPE to 3)
}
