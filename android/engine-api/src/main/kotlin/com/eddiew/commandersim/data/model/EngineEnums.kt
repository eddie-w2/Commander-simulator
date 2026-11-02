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
enum class EndCondition(val label: String) {
    COMBAT_DAMAGE("Combat"),
    COMMANDER_DAMAGE("Commander dmg"),
    NONCOMBAT_DAMAGE("Burn / direct"),
    LIFE_LOSS("Drain / life loss"),
    POISON("Poison"),
    MILL("Mill / decking"),
    ALT_WIN("Alt win"),
    TIMEOUT("Timeout"),
    ADJUDICATED("Round limit (scored)"),
}
