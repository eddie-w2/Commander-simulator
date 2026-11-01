package com.eddiew.commandersim.data.model

import kotlinx.serialization.Serializable

/** Broad card type used to bucket statistics. Taken from the card's type line (via Scryfall) when known. */
@Serializable
enum class CardType(val label: String) {
    CREATURE("Creature"),
    INSTANT("Instant"),
    SORCERY("Sorcery"),
    ARTIFACT("Artifact"),
    ENCHANTMENT("Enchantment"),
    PLANESWALKER("Planeswalker"),
    BATTLE("Battle"),
    LAND("Land"),
    UNKNOWN("Unknown");

    companion object {
        /** Picks the most relevant type from a full type line such as "Legendary Artifact Creature — Golem". */
        fun fromTypeLine(typeLine: String?): CardType {
            if (typeLine.isNullOrBlank()) return UNKNOWN
            val front = typeLine.substringBefore("//")
            return when {
                "Land" in front -> LAND
                "Creature" in front -> CREATURE
                "Planeswalker" in front -> PLANESWALKER
                "Battle" in front -> BATTLE
                "Instant" in front -> INSTANT
                "Sorcery" in front -> SORCERY
                "Artifact" in front -> ARTIFACT
                "Enchantment" in front -> ENCHANTMENT
                else -> UNKNOWN
            }
        }
    }
}

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
) {
    val cardCount: Int get() = commanders.sumOf { it.quantity } + cards.sumOf { it.quantity }
    val commanderLabel: String get() = commanders.joinToString(" + ") { it.name }.ifEmpty { "No commander" }
    val colorIdentity: String
        get() {
            val all = (commanders.mapNotNull { it.colors }).joinToString("")
            return "WUBRG".filter { it in all }.ifEmpty { if (commanders.any { it.colors != null }) "C" else "" }
        }
}

/** A deck's role in a simulation. */
@Serializable
enum class DeckRole { CANDIDATE, OPPONENT }
