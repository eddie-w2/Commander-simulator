package com.eddiew.commandersim.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DeckCard(
    val name: String,
    val quantity: Int = 1,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    // Filled in from Scryfall after import; null until then.
    val typeLine: String? = null,
    val manaValue: Double? = null,
    val colors: String? = null,
) {
    val type: CardType get() = CardType.fromTypeLine(typeLine)
}

@Serializable
data class Deck(
    val id: String,
    val name: String,
    val commanders: List<DeckCard>,
    val cards: List<DeckCard>,
    val createdAt: Long,
    val enriched: Boolean = false,
    /** Set when this deck is a variant of another deck (for A/B testing card swaps). */
    val parentId: String? = null,
    /** Where it was imported from, if a link was used. */
    val sourceUrl: String? = null,
    /** The user owns this deck in paper. */
    val owned: Boolean = false,
    /** When the deck was last synced from [sourceUrl]. */
    val syncedAt: Long? = null,
) {
    val cardCount: Int get() = commanders.sumOf { it.quantity } + cards.sumOf { it.quantity }
    val commanderLabel: String get() = commanders.joinToString(" + ") { it.name }.ifEmpty { "No commander" }
    val colorIdentity: String
        get() {
            val all = (commanders.mapNotNull { it.colors }).joinToString("")
            return "WUBRG".filter { it in all }.ifEmpty { if (commanders.any { it.colors != null }) "C" else "" }
        }
}

/** Cards removed and added going from [base] to [variant], by name (quantities included). */
data class DeckDiff(val removed: List<DeckCard>, val added: List<DeckCard>)

fun diffDecks(base: Deck, variant: Deck): DeckDiff {
    fun counts(d: Deck) = (d.commanders + d.cards).groupBy { it.name.lowercase() }
        .mapValues { (_, v) -> v.first() to v.sumOf { it.quantity } }
    val a = counts(base)
    val b = counts(variant)
    val removed = a.mapNotNull { (k, v) ->
        val left = v.second - (b[k]?.second ?: 0)
        if (left > 0) v.first.copy(quantity = left) else null
    }
    val added = b.mapNotNull { (k, v) ->
        val extra = v.second - (a[k]?.second ?: 0)
        if (extra > 0) v.first.copy(quantity = extra) else null
    }
    return DeckDiff(removed.sortedBy { it.name }, added.sortedBy { it.name })
}

/** A saved copy of a deck taken before it was changed (sync, restore). */
@Serializable
data class DeckVersion(
    val versionId: String,
    val deckId: String,
    val savedAt: Long,
    val reason: String,
    val name: String,
    val commanders: List<DeckCard>,
    val cards: List<DeckCard>,
) {
    val cardCount: Int get() = commanders.sumOf { it.quantity } + cards.sumOf { it.quantity }
    fun asDeck(current: Deck) = current.copy(name = name, commanders = commanders, cards = cards)
}
