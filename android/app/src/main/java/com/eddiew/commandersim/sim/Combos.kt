package com.eddiew.commandersim.sim

/**
 * Well-known Commander combos that win on the spot once every piece is on the battlefield or cast from hand.
 * The quick model can't play these out step by step, so a deck that assembles one (with the mana to cast the pieces
 * still in hand, plus [extraMana]) wins, unless the final piece is countered.
 */
data class Combo(val name: String, val pieces: List<String>, val extraMana: Int = 0, val needsRockMana: Int = 0) {
    val keys: List<String> = pieces.map { it.lowercase() }
}

object Combos {
    val all: List<Combo> = listOf(
        Combo("Thassa's Oracle + Demonic Consultation", listOf("Thassa's Oracle", "Demonic Consultation")),
        Combo("Thassa's Oracle + Tainted Pact", listOf("Thassa's Oracle", "Tainted Pact")),
        Combo("Laboratory Maniac + Demonic Consultation", listOf("Laboratory Maniac", "Demonic Consultation")),
        Combo("Jace, Wielder of Mysteries + Demonic Consultation", listOf("Jace, Wielder of Mysteries", "Demonic Consultation")),
        Combo("Exquisite Blood + Sanguine Bond", listOf("Exquisite Blood", "Sanguine Bond"), extraMana = 1),
        Combo("Exquisite Blood + Vito, Thorn of the Dusk Rose", listOf("Exquisite Blood", "Vito, Thorn of the Dusk Rose"), extraMana = 1),
        Combo("Dramatic Reversal + Isochron Scepter", listOf("Isochron Scepter", "Dramatic Reversal"), extraMana = 2, needsRockMana = 3),
        Combo("Kiki-Jiki + Zealous Conscripts", listOf("Kiki-Jiki, Mirror Breaker", "Zealous Conscripts")),
        Combo("Kiki-Jiki + Pestermite", listOf("Kiki-Jiki, Mirror Breaker", "Pestermite")),
        Combo("Kiki-Jiki + Deceiver Exarch", listOf("Kiki-Jiki, Mirror Breaker", "Deceiver Exarch")),
        Combo("Kiki-Jiki + Felidar Guardian", listOf("Kiki-Jiki, Mirror Breaker", "Felidar Guardian")),
        Combo("Kiki-Jiki + Restoration Angel", listOf("Kiki-Jiki, Mirror Breaker", "Restoration Angel")),
        Combo("Splinter Twin + Pestermite", listOf("Splinter Twin", "Pestermite")),
        Combo("Splinter Twin + Deceiver Exarch", listOf("Splinter Twin", "Deceiver Exarch")),
        Combo("Saheeli Rai + Felidar Guardian", listOf("Saheeli Rai", "Felidar Guardian")),
        Combo("Heliod, Sun-Crowned + Walking Ballista", listOf("Heliod, Sun-Crowned", "Walking Ballista"), extraMana = 3),
        Combo("Mikaeus, the Unhallowed + Triskelion", listOf("Mikaeus, the Unhallowed", "Triskelion")),
        Combo("Niv-Mizzet, Parun + Curiosity", listOf("Niv-Mizzet, Parun", "Curiosity")),
        Combo("Niv-Mizzet, Parun + Ophidian Eye", listOf("Niv-Mizzet, Parun", "Ophidian Eye")),
        Combo("Niv-Mizzet, Parun + Tandem Lookout", listOf("Niv-Mizzet, Parun", "Tandem Lookout")),
        Combo("Niv-Mizzet, the Firemind + Curiosity", listOf("Niv-Mizzet, the Firemind", "Curiosity")),
        Combo("Dualcaster Mage + Twinflame", listOf("Dualcaster Mage", "Twinflame")),
        Combo("Dualcaster Mage + Heat Shimmer", listOf("Dualcaster Mage", "Heat Shimmer")),
        Combo("Painter's Servant + Grindstone", listOf("Painter's Servant", "Grindstone"), extraMana = 3),
        Combo("Godo, Bandit Warlord + Helm of the Host", listOf("Godo, Bandit Warlord", "Helm of the Host"), extraMana = 5),
        Combo("Gravecrawler + Phyrexian Altar + Blood Artist", listOf("Gravecrawler", "Phyrexian Altar", "Blood Artist")),
        Combo("Underworld Breach + Lion's Eye Diamond + Brain Freeze", listOf("Underworld Breach", "Lion's Eye Diamond", "Brain Freeze"), extraMana = 1),
        Combo("Bloodchief Ascension + Mindcrank", listOf("Bloodchief Ascension", "Mindcrank")),
        Combo("Sanguine Bond + Exquisite Blood", listOf("Sanguine Bond", "Exquisite Blood"), extraMana = 1),
        Combo("Walking Ballista + Basalt Monolith + Rings of Brighthearth", listOf("Walking Ballista", "Basalt Monolith", "Rings of Brighthearth"), extraMana = 3),
        Combo("Kinnan + Basalt Monolith (with an outlet)", listOf("Kinnan, Bonder Prodigy", "Basalt Monolith", "Walking Ballista")),
        Combo("Sword of the Meek + Thopter Foundry", listOf("Sword of the Meek", "Thopter Foundry"), extraMana = 6),
        Combo("Peregrine Drake + Deadeye Navigator (with a payoff)", listOf("Peregrine Drake", "Deadeye Navigator", "Walking Ballista")),
    ).distinctBy { it.keys.sorted() }

    /** Combos whose pieces are all in [cardNames] (lowercase, front faces). */
    fun inDeck(cardNames: Set<String>): List<Combo> = all.filter { c -> c.keys.all { it in cardNames } }
}
