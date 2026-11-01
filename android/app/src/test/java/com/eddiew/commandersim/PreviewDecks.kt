package com.eddiew.commandersim

import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard

/** Realistic-looking decks for screenshot tests (types and mana values pre-filled, as if synced from Scryfall). */
object PreviewDecks {
    private fun c(name: String, type: String, mv: Int) = DeckCard(name, 1, typeLine = type, manaValue = mv.toDouble())

    private val staples = listOf(
        c("Sol Ring", "Artifact", 1), c("Arcane Signet", "Artifact", 2), c("Mind Stone", "Artifact", 2),
        c("Fellwar Stone", "Artifact", 2), c("Command Tower", "Land", 0), c("Lightning Greaves", "Artifact — Equipment", 2),
        c("Swiftfoot Boots", "Artifact — Equipment", 2), c("Reliquary Tower", "Land", 0), c("Exotic Orchard", "Land", 0),
    )

    private fun deck(id: String, name: String, commander: DeckCard, colors: String, basics: List<String>, cards: List<DeckCard>): Deck {
        val nonBasic = staples + cards
        val landsNeeded = 100 - 1 - nonBasic.size
        val per = landsNeeded / basics.size
        val basicCards = basics.mapIndexed { i, b ->
            DeckCard(b, if (i == 0) landsNeeded - per * (basics.size - 1) else per, typeLine = "Basic Land — $b", manaValue = 0.0)
        }
        return Deck(id, name, listOf(commander.copy(colors = colors)), nonBasic + basicCards, 0L, enriched = true)
    }

    val krenko = deck(
        "krenko", "Krenko Goblins", c("Krenko, Mob Boss", "Legendary Creature — Goblin Warrior", 4), "R", listOf("Mountain"),
        listOf(
            c("Goblin Matron", "Creature — Goblin", 3), c("Skirk Prospector", "Creature — Goblin", 1), c("Goblin Warchief", "Creature — Goblin Warrior", 3),
            c("Goblin Chieftain", "Creature — Goblin", 3), c("Beetleback Chief", "Creature — Goblin Warrior", 4), c("Siege-Gang Commander", "Creature — Goblin", 5),
            c("Goblin Rabblemaster", "Creature — Goblin Warrior", 3), c("Legion Warboss", "Creature — Goblin Soldier", 3), c("Goblin Instigator", "Creature — Goblin", 2),
            c("Mogg War Marshal", "Creature — Goblin Warrior", 2), c("Goblin Ringleader", "Creature — Goblin", 4), c("Krenko, Tin Street Kingpin", "Legendary Creature — Goblin", 3),
            c("Muxus, Goblin Grandee", "Legendary Creature — Goblin Noble", 6), c("Purphoros, God of the Forge", "Legendary Enchantment Creature — God", 4),
            c("Impact Tremors", "Enchantment", 2), c("Goblin Bombardment", "Enchantment", 2), c("Shared Animosity", "Enchantment", 3),
            c("Blasphemous Act", "Sorcery", 9), c("Chaos Warp", "Instant", 3), c("Lightning Bolt", "Instant", 1), c("Vandalblast", "Sorcery", 1),
            c("Shatterskull Smashing", "Land", 0), c("Skullclamp", "Artifact — Equipment", 1), c("Coat of Arms", "Artifact", 5),
            c("Goblin Piledriver", "Creature — Goblin Warrior", 2), c("Goblin King", "Creature — Goblin", 3), c("Conspicuous Snoop", "Creature — Goblin Rogue", 2),
            c("Goblin Recruiter", "Creature — Goblin", 2), c("Battle Hymn", "Sorcery", 2), c("Wheel of Fortune", "Sorcery", 3),
            c("Faithless Looting", "Sorcery", 1), c("Ruby Medallion", "Artifact", 2), c("Throne of the God-Pharaoh", "Legendary Artifact", 2),
            c("Goblin Sharpshooter", "Creature — Goblin", 3), c("Feed the Swarm", "Sorcery", 2), c("Dockside Extortionist", "Creature — Goblin Pirate", 2),
        ),
    )

    val atraxa = deck(
        "atraxa", "Atraxa Superfriends", c("Atraxa, Praetors' Voice", "Legendary Creature — Phyrexian Angel Horror", 4), "WUBG",
        listOf("Plains", "Island", "Swamp", "Forest"),
        listOf(
            c("Doubling Season", "Enchantment", 5), c("Teferi, Hero of Dominaria", "Legendary Planeswalker — Teferi", 5),
            c("Vraska, Golgari Queen", "Legendary Planeswalker — Vraska", 4), c("Elspeth, Sun's Champion", "Legendary Planeswalker — Elspeth", 6),
            c("Nissa, Who Shakes the World", "Legendary Planeswalker — Nissa", 5), c("Oko, Thief of Crowns", "Legendary Planeswalker — Oko", 3),
            c("The Wandering Emperor", "Legendary Planeswalker — Emperor", 4), c("Deepglow Skate", "Creature — Fish", 5),
            c("Swords to Plowshares", "Instant", 1), c("Path to Exile", "Instant", 1), c("Anguished Unmaking", "Instant", 3),
            c("Assassin's Trophy", "Instant", 2), c("Toxic Deluge", "Sorcery", 3), c("Farewell", "Sorcery", 6), c("Counterspell", "Instant", 2),
            c("Rhystic Study", "Enchantment", 3), c("Smothering Tithe", "Enchantment", 4), c("Cultivate", "Sorcery", 3),
            c("Kodama's Reach", "Sorcery", 3), c("Farseek", "Sorcery", 2), c("Oath of Teferi", "Legendary Enchantment", 5),
            c("Inexorable Tide", "Enchantment", 5), c("Flux Channeler", "Creature — Human Wizard", 3), c("Evolution Sage", "Creature — Elf Druid", 3),
            c("Tamiyo's Safekeeping", "Instant", 1), c("Heroic Intervention", "Instant", 2), c("Cyclonic Rift", "Instant", 2),
            c("Sylvan Library", "Enchantment", 1), c("Chromatic Lantern", "Artifact", 3), c("Talisman of Dominance", "Artifact", 2),
            c("Breeding Pool", "Land — Forest Island", 0), c("Overgrown Tomb", "Land — Swamp Forest", 0), c("Godless Shrine", "Land — Plains Swamp", 0),
        ),
    )

    val meren = deck(
        "meren", "Meren Graveyard", c("Meren of Clan Nel Toth", "Legendary Creature — Human Shaman", 4), "BG", listOf("Swamp", "Forest"),
        listOf(
            c("Sakura-Tribe Elder", "Creature — Snake Shaman", 2), c("Spore Frog", "Creature — Frog", 1), c("Eternal Witness", "Creature — Human Shaman", 3),
            c("Ravenous Chupacabra", "Creature — Beast Horror", 4), c("Shriekmaw", "Creature — Elemental", 5), c("Gray Merchant of Asphodel", "Creature — Zombie", 5),
            c("Plaguecrafter", "Creature — Human Shaman", 3), c("Fleshbag Marauder", "Creature — Zombie Warrior", 3), c("Grave Titan", "Creature — Giant", 6),
            c("Sheoldred, the Apocalypse", "Legendary Creature — Phyrexian Praetor", 4), c("Viscera Seer", "Creature — Vampire Wizard", 1),
            c("Ashnod's Altar", "Artifact", 3), c("Phyrexian Altar", "Artifact", 3), c("Skullclamp", "Artifact — Equipment", 1),
            c("Pernicious Deed", "Enchantment", 3), c("Damnation", "Sorcery", 4), c("Beast Within", "Instant", 3), c("Putrefy", "Instant", 3),
            c("Infernal Grasp", "Instant", 2), c("Deadly Rollick", "Instant", 4), c("Phyrexian Arena", "Enchantment", 3), c("Necropotence", "Enchantment", 3),
            c("Buried Alive", "Sorcery", 3), c("Living Death", "Sorcery", 5), c("Nature's Lore", "Sorcery", 2), c("Three Visits", "Sorcery", 2),
            c("Sidisi, Brood Tyrant", "Legendary Creature — Naga Shaman", 4), c("Mulldrifter", "Creature — Elemental", 5),
            c("Woodland Cemetery", "Land", 0), c("Llanowar Wastes", "Land", 0), c("Bojuka Bog", "Land", 0), c("Golgari Signet", "Artifact", 2),
        ),
    )

    val edgar = deck(
        "edgar", "Edgar Vampires", c("Edgar Markov", "Legendary Creature — Vampire Knight", 6), "RWB", listOf("Plains", "Swamp", "Mountain"),
        listOf(
            c("Cordial Vampire", "Creature — Vampire", 2), c("Stromkirk Captain", "Creature — Vampire Soldier", 3), c("Legion Lieutenant", "Creature — Vampire Knight", 2),
            c("Sorin, Imperious Bloodlord", "Legendary Planeswalker — Sorin", 3), c("Bloodline Keeper", "Creature — Vampire", 4), c("Vito, Thorn of the Dusk Rose", "Legendary Creature — Vampire Cleric", 3),
            c("Sanctum Seeker", "Creature — Vampire Knight", 4), c("Captivating Vampire", "Creature — Vampire", 3), c("Vampire Nocturnus", "Creature — Vampire", 4),
            c("Exquisite Blood", "Enchantment", 5), c("Sanguine Bond", "Enchantment", 5), c("Anguished Unmaking", "Instant", 3), c("Vindicate", "Sorcery", 3),
            c("Swords to Plowshares", "Instant", 1), c("Terminate", "Instant", 2), c("Blasphemous Act", "Sorcery", 9), c("Austere Command", "Sorcery", 6),
            c("New Blood", "Sorcery", 4), c("Kindred Discovery", "Enchantment", 5), c("Shared Animosity", "Enchantment", 3), c("Door of Destinies", "Artifact", 4),
            c("Patron of the Vein", "Creature — Vampire Shaman", 6), c("Olivia, Mobilized for War", "Legendary Creature — Vampire Knight", 3),
            c("Mavren Fein, Dusk Apostle", "Legendary Creature — Vampire Cleric", 3), c("Elenda, the Dusk Rose", "Legendary Creature — Vampire Knight", 4),
            c("Talisman of Indulgence", "Artifact", 2), c("Boros Signet", "Artifact", 2), c("Phyrexian Arena", "Enchantment", 3),
        ),
    )

    val talrand = deck(
        "talrand", "Talrand Spellslinger", c("Talrand, Sky Summoner", "Legendary Creature — Merfolk Wizard", 4), "U", listOf("Island"),
        listOf(
            c("Counterspell", "Instant", 2), c("Mana Drain", "Instant", 2), c("Swan Song", "Instant", 1), c("Fierce Guardianship", "Instant", 3),
            c("Arcane Denial", "Instant", 2), c("Negate", "Instant", 2), c("An Offer You Can't Refuse", "Instant", 1), c("Dovin's Veto", "Instant", 2),
            c("Brainstorm", "Instant", 1), c("Ponder", "Sorcery", 1), c("Preordain", "Sorcery", 1), c("Opt", "Instant", 1), c("Frantic Search", "Instant", 3),
            c("Rapid Hybridization", "Instant", 1), c("Pongify", "Instant", 1), c("Cyclonic Rift", "Instant", 2), c("Evacuation", "Instant", 5),
            c("Rhystic Study", "Enchantment", 3), c("Mystic Remora", "Enchantment", 1), c("Young Pyromancer", "Creature — Human Shaman", 2),
            c("Murmuring Mystic", "Creature — Human Wizard", 4), c("Archmage Emeritus", "Creature — Human Wizard", 4), c("Baral, Chief of Compliance", "Legendary Creature — Human Wizard", 2),
            c("Sea Gate Stormcaller", "Creature — Human Wizard", 2), c("Thousand-Year Storm", "Enchantment", 6), c("Reality Shift", "Instant", 2),
            c("High Tide", "Instant", 1), c("Snap", "Instant", 2), c("Gitaxian Probe", "Sorcery", 1), c("Delay", "Instant", 2),
            c("Mystic Sanctuary", "Land", 0), c("Thought Vessel", "Artifact", 2),
        ),
    )

    val omnath = deck(
        "omnath", "Omnath Landfall", c("Omnath, Locus of Creation", "Legendary Creature — Elemental", 4), "WURG",
        listOf("Plains", "Island", "Mountain", "Forest"),
        listOf(
            c("Lotus Cobra", "Creature — Snake", 2), c("Tatyova, Benthic Druid", "Legendary Creature — Merfolk Druid", 5), c("Avenger of Zendikar", "Creature — Elemental", 7),
            c("Field of the Dead", "Land", 0), c("Scute Swarm", "Creature — Insect", 3), c("Rampaging Baloths", "Creature — Beast", 6),
            c("Felidar Retreat", "Enchantment", 4), c("Amulet of Vigor", "Artifact", 1), c("Azusa, Lost but Seeking", "Legendary Creature — Human Monk", 3),
            c("Dryad of the Ilysian Grove", "Enchantment Creature — Nymph Dryad", 3), c("Oracle of Mul Daya", "Creature — Elf Shaman", 4),
            c("Explore", "Sorcery", 2), c("Growth Spiral", "Instant", 2), c("Rampant Growth", "Sorcery", 2), c("Skyshroud Claim", "Sorcery", 4),
            c("Beast Within", "Instant", 3), c("Chaos Warp", "Instant", 3), c("Generous Gift", "Instant", 3), c("Krosan Grip", "Instant", 3),
            c("Nature's Claim", "Instant", 1), c("Heroic Intervention", "Instant", 2), c("Counterspell", "Instant", 2), c("Evolving Wilds", "Land", 0),
            c("Terramorphic Expanse", "Land", 0), c("Fabled Passage", "Land", 0), c("Windswept Heath", "Land", 0), c("Misty Rainforest", "Land", 0),
            c("Guardian Project", "Enchantment", 4), c("Beast Whisperer", "Creature — Elf Druid", 4), c("Rabid Bite", "Sorcery", 2),
        ),
    )

    val all = listOf(krenko, atraxa, meren, edgar, talrand, omnath)
    val candidates = listOf(krenko, atraxa, meren)
    val opponents = listOf(edgar, talrand, omnath)
}
