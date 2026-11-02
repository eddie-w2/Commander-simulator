package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.DeckCard

/** Colour bits used for mana: W U B R G, plus C for colourless-only ({C}) costs and sources. */
object ManaColor {
    const val W = 1; const val U = 2; const val B = 4; const val R = 8; const val G = 16; const val C = 32
    const val ALL = W or U or B or R or G
    val ORDER = listOf('W' to W, 'U' to U, 'B' to B, 'R' to R, 'G' to G)

    fun of(ch: Char): Int = when (ch.uppercaseChar()) {
        'W' -> W; 'U' -> U; 'B' -> B; 'R' -> R; 'G' -> G; 'C' -> C; else -> 0
    }
    fun mask(colors: String?): Int = colors.orEmpty().fold(0) { m, c -> m or of(c) }
    fun letters(mask: Int): String = ORDER.filter { mask and it.second != 0 }.joinToString("") { it.first.toString() }
}

/** A mana cost: each coloured pip lists the colours that can pay it; the rest is generic. */
data class ManaCost(val pips: List<Int>, val generic: Int, val x: Int = 0) {
    val total: Int get() = pips.size + generic
    /** Colour bits this cost needs at least one of (for each pip). */
    val colorsNeeded: Int get() = pips.fold(0) { m, p -> m or p }

    companion object {
        val FREE = ManaCost(emptyList(), 0)

        /**
         * Parses "{2}{G}{G}", "{X}{R}", "{W/U}", "{2/B}", "{G/P}", "{C}", "{S}". X counts as 0; Phyrexian pips are
         * treated as payable with life (free); "{2/B}" counts as {B}; snow as generic.
         */
        fun parse(cost: String?, fallbackMv: Int = 0): ManaCost {
            if (cost.isNullOrBlank()) return ManaCost(emptyList(), fallbackMv)
            val face = cost.substringBefore(" // ")
            val pips = mutableListOf<Int>()
            var generic = 0
            var x = 0
            rx("""\{([^}]+)\}""").findAll(face).forEach { m ->
                val sym = m.groupValues[1].uppercase()
                when {
                    sym.toIntOrNull() != null -> generic += sym.toInt()
                    sym == "X" || sym == "Y" || sym == "Z" -> x++
                    sym == "S" -> generic += 1
                    sym.endsWith("/P") -> Unit
                    sym.contains('/') -> {
                        val parts = sym.split('/')
                        val mask = parts.fold(0) { acc, p -> acc or (p.singleOrNull()?.let { ManaColor.of(it) } ?: 0) }
                        if (mask != 0) pips += mask else generic += 1
                    }
                    sym.length == 1 && ManaColor.of(sym[0]) != 0 -> pips += ManaColor.of(sym[0])
                    else -> generic += 1
                }
            }
            return ManaCost(pips, generic, x)
        }
    }
}

/** What a card does in the quick model. A card can have several roles. */
enum class Role { LAND, RAMP_LAND, ROCK, DORK, DRAW, ENGINE, REMOVAL, WIPE, COUNTER, TUTOR, BURN, DRAIN, TOKENS, LIFEGAIN, PROTECTION, RECURSION, TRICK, CREATURE, OTHER }

/** How a removal spell gets rid of its target. */
enum class RemovalKind { DESTROY, EXILE, BOUNCE, DAMAGE, MINUS, FIGHT }

enum class TutorDest { HAND, TOP, BATTLEFIELD, GRAVEYARD }

/** A spell's additional cost. */
enum class ExtraCost { DISCARD, SAC_CREATURE, SAC_ARTIFACT, SAC_LAND, LIFE }

/** Ways to cast a card again from the graveyard. */
enum class GyMode { FLASHBACK, RETRACE, JUMP_START, ESCAPE, UNEARTH, EMBALM, ETERNALIZE, ENCORE, DISTURB, RECAST }

/** Cost reductions a spell gives itself. */
enum class SelfReduce { CONVOKE, IMPROVISE, DELVE, AFFINITY, UNDAUNTED, PER_SCALE }

/** Always-on effects of a permanent that change how the game is played around it. */
data class Statics(
    /** Keywords for your creatures that have a +1/+1 counter. */
    val countersGrant: Int = 0,
    /** Your other creatures enter with this many extra +1/+1 counters (of [etbCountersTribe], if set). */
    val etbCounters: Int = 0,
    val etbCountersTribe: String? = null,
    /** "You gain that much life plus N instead." */
    val lifeGainPlus: Int = 0,
    val noLifeGain: Boolean = false,
    /** Your creatures are every creature type. */
    val allTypes: Boolean = false,
    val extraLand: Boolean = false,
    /** Free to cast while you control a commander. */
    val freeWithCommander: Boolean = false,
    /** Seedborn Muse: your permanents untap on every turn. */
    val untapEachTurn: Boolean = false,
    val damageDouble: Boolean = false,
    val damageHalved: Boolean = false,
    val skipDraw: Boolean = false,
    /** Fog Bank / Guard Gomazoa: combat damage to (and by) this creature is prevented. */
    val noCombatDamageTo: Boolean = false,
    val noCombatDamageBy: Boolean = false,
    /** "Creatures with flying can't attack you." */
    val flyersCantAttackYou: Boolean = false,
    /** Ghostly Prison / Propaganda: each creature attacking you costs this much. */
    val attackTax: Int = 0,
    /** Champion of Lambholt: creatures with less power than this can't block (it / your creatures). */
    val smallCantBlock: Boolean = false,
    val smallCantBlockTeam: Boolean = false,
    /** Academy Manufactor: Clue, Food and Treasure come as one of each. */
    val tripleArtifactTokens: Boolean = false,
    val lifeGainDouble: Boolean = false,
    /** "Creatures your opponents control enter tapped." */
    val oppCreaturesEnterTapped: Boolean = false,
    /** Panharmonicon (artifacts and creatures) / Ancient Greenwarden (lands): entering triggers happen twice. */
    val doubleEtbTriggers: Boolean = false,
    val doubleLandfall: Boolean = false,
    /** Theros gods: not a creature until devotion to these colours reaches five. */
    val devotionGod: Int = 0,
    /** Cumulative upkeep: kept for a couple of turns, then let go. */
    val cumulativeUpkeep: Boolean = false,
) {
    fun describe(): List<String> = listOfNotNull(
        "creatures with +1/+1 counters have ${Kw.names(countersGrant).joinToString(", ")}".takeIf { countersGrant != 0 },
        "your other ${etbCountersTribe ?: "creature"}s enter with +$etbCounters counter(s)".takeIf { etbCounters > 0 },
        "life gained +$lifeGainPlus".takeIf { lifeGainPlus > 0 }, "players can't gain life".takeIf { noLifeGain },
        "your creatures are every type".takeIf { allTypes }, "an extra land drop each turn".takeIf { extraLand },
        "free while you control a commander".takeIf { freeWithCommander }, "untaps on every player's turn".takeIf { untapEachTurn },
        "damage to opponents doubled".takeIf { damageDouble }, "damage to you halved".takeIf { damageHalved }, "skip your draw step".takeIf { skipDraw },
        "takes no combat damage".takeIf { noCombatDamageTo }, "deals no combat damage".takeIf { noCombatDamageBy },
        "flyers can't attack you".takeIf { flyersCantAttackYou }, "attackers pay $attackTax each".takeIf { attackTax > 0 },
        "smaller creatures can't block it".takeIf { smallCantBlock }, "smaller creatures can't block your creatures".takeIf { smallCantBlockTeam },
        "Clue/Food/Treasure come as one of each".takeIf { tripleArtifactTokens }, "life gained doubled".takeIf { lifeGainDouble },
        "opponents' creatures enter tapped".takeIf { oppCreaturesEnterTapped },
        "entering triggers happen twice".takeIf { doubleEtbTriggers }, "landfall triggers happen twice".takeIf { doubleLandfall },
        "a creature only with devotion 5".takeIf { devotionGod != 0 }, "cumulative upkeep: let go after two turns".takeIf { cumulativeUpkeep },
    )

    companion object {
        val NONE = Statics()
        fun from(text: String): Statics {
            val etb = rx("""each other ([a-z-]+ )?creature you control (of the chosen type )?enters with (an additional|a number of additional|two additional|an additional x) \+1/\+1 counters?""").find(text)
            return Statics(
                countersGrant = rx("""each creature you control with (a|one or more) \+1/\+1 counters? on it has ([a-z ,]+)""").find(text)?.let { Kw.of(it.groupValues[2]) } ?: 0,
                etbCounters = etb?.let { if (it.groupValues[3].startsWith("two")) 2 else 1 } ?: 0,
                etbCountersTribe = etb?.let { m -> if (m.groupValues[2].isNotEmpty()) "chosen" else m.groupValues[1].trim().takeIf { t -> t.isNotEmpty() && !t.startsWith("non") } },
                lifeGainPlus = rx("""if you would gain life, you gain that much life plus (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                noLifeGain = rx("""(players|your opponents) can't gain life""").containsMatchIn(text),
                allTypes = "creatures you control are every creature type" in text,
                extraLand = rx("""(you|each player) may play an additional land""").containsMatchIn(text),
                freeWithCommander = "if you control a commander, you may cast this spell without paying its mana cost" in text,
                untapEachTurn = rx("""untap all (permanents|[a-z/ ]+creatures) you control during each other player's untap step""").containsMatchIn(text),
                damageDouble = rx("""would deal damage to (an opponent|a permanent an opponent controls)[^.]*double that damage""").containsMatchIn(text),
                damageHalved = rx("""would deal damage to you[^.]*prevent half that damage""").containsMatchIn(text),
                skipDraw = text.lines().any { it.trim().startsWith("skip your draw step") },
                noCombatDamageTo = rx("""prevent all combat damage that would be dealt to (and dealt by )?~(\.|$)""").containsMatchIn(text),
                noCombatDamageBy = rx("""prevent all combat damage that would be dealt to and dealt by ~""").containsMatchIn(text),
                flyersCantAttackYou = "creatures with flying can't attack you" in text,
                attackTax = rx("""creatures can't attack you[^.]*unless their controller pays \{(\d+)\}""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                smallCantBlock = rx("""creatures with power less than ~'s power can't block it""").containsMatchIn(text),
                smallCantBlockTeam = rx("""creatures with power less than ~'s power can't block creatures you control""").containsMatchIn(text),
                tripleArtifactTokens = "if you would create a clue, food, or treasure token, instead create one of each" in text,
                lifeGainDouble = "if you would gain life, you gain twice that much life instead" in text,
                oppCreaturesEnterTapped = rx("""creatures your opponents control enter tapped""").containsMatchIn(text),
                doubleEtbTriggers = rx("""if an artifact or creature entering causes a triggered ability[^.]*triggers an additional time""").containsMatchIn(text),
                doubleLandfall = rx("""if a land entering causes a triggered ability[^.]*triggers an additional time""").containsMatchIn(text),
                devotionGod = rx("""as long as your devotion to ([a-z]+)( and ([a-z]+))? is less than (five|seven), ~ isn't a creature""").find(text)?.let { m ->
                    listOf(m.groupValues[1], m.groupValues[3]).fold(0) { acc, w -> acc or when (w) { "white" -> ManaColor.W; "blue" -> ManaColor.U; "black" -> ManaColor.B; "red" -> ManaColor.R; "green" -> ManaColor.G; else -> 0 } }
                } ?: 0,
                cumulativeUpkeep = text.lines().any { it.trim().startsWith("cumulative upkeep") },
            )
        }
    }
}

/** Keyword costs and casting options read from a card's text. */
data class CastOptions(
    val cycleCost: ManaCost? = null,
    /** Landcycling: the cycled card finds a land. */
    val cycleForLand: Boolean = false,
    val kicker: ManaCost? = null,
    val multikicker: Boolean = false,
    val gyCost: ManaCost? = null,
    val gyMode: GyMode? = null,
    val evoke: ManaCost? = null,
    val rebound: Boolean = false,
    val overload: ManaCost? = null,
    val selfReduce: SelfReduce? = null,
    /** "Costs {N} less for each …": N and what's counted. */
    val reducePer: Int = 1,
    val reduceScale: Scale? = null,
    /** Squad / offspring / replicate: pay again for a token copy. */
    val copyCost: ManaCost? = null,
    val copyMax: Int = 0,
    val demonstrate: Boolean = false,
    val storm: Boolean = false,
    val buyback: ManaCost? = null,
    val crew: Int = 0,
    /** Suspend N—{cost}: cast for [suspend] now, resolves after [suspendTurns] of your turns. */
    val suspend: ManaCost? = null,
    val suspendTurns: Int = 0,
) {
    fun describe(): List<String> = listOfNotNull(
        cycleCost?.let { "${if (cycleForLand) "landcycling" else "cycling"} ${it.total}" },
        kicker?.let { "${if (multikicker) "multikicker" else "kicker"} ${it.total}" },
        gyCost?.let { "${gyMode?.name?.lowercase()?.replace('_', '-')} ${it.total}: cast again from the graveyard" },
        evoke?.let { "evoke ${it.total}" },
        "rebound".takeIf { rebound },
        overload?.let { "overload ${it.total}: hits every opponent's" },
        selfReduce?.let { "costs less (${it.name.lowercase().replace('_', ' ')})" },
        copyCost?.let { "pay ${it.total} for a copy (up to $copyMax)" },
        "demonstrate: cast twice".takeIf { demonstrate },
        "storm".takeIf { storm },
        buyback?.let { "buyback ${it.total}" },
        "crew $crew".takeIf { crew > 0 },
        suspend?.let { "suspend $suspendTurns for ${it.total}" },
    )

    companion object {
        val NONE = CastOptions()

        /** Reads keyword lines ("cycling {2}", "kicker {1}{G}", "flashback—{1}{U}, pay 3 life") from normalised text. */
        fun from(lines: List<String>, cost: ManaCost): CastOptions {
            fun costAfter(l: String, word: String): ManaCost? = l.removePrefix(word).trimStart('—', ' ').let { rest ->
                rx("""^((?:\{[^}]+\})+)""").find(rest)?.groupValues?.get(1)?.let { ManaCost.parse(it) }
            }
            var o = NONE
            for (l in lines) {
                val w = l.substringBefore(' ').substringBefore('—')
                o = when {
                    rx("""^(basic landcycling|[a-z]+cycling)\b""").containsMatchIn(l) && !l.startsWith("cycling") ->
                        o.copy(cycleCost = costAfter(l, l.substringBefore(" {").substringBefore("—")) ?: ManaCost(emptyList(), 2), cycleForLand = true)
                    l.startsWith("cycling") -> o.copy(cycleCost = costAfter(l, "cycling") ?: ManaCost(emptyList(), 2))
                    l.startsWith("multikicker") -> o.copy(kicker = costAfter(l, "multikicker"), multikicker = true)
                    l.startsWith("kicker") -> o.copy(kicker = costAfter(l, "kicker"))
                    w in gyWords -> o.copy(gyCost = costAfter(l, w) ?: cost, gyMode = gyWords[w])
                    l.startsWith("evoke") -> o.copy(evoke = costAfter(l, "evoke"))
                    l == "rebound" -> o.copy(rebound = true)
                    l.startsWith("overload") -> o.copy(overload = costAfter(l, "overload"))
                    l == "convoke" -> o.copy(selfReduce = SelfReduce.CONVOKE)
                    l == "improvise" -> o.copy(selfReduce = SelfReduce.IMPROVISE)
                    l == "delve" -> o.copy(selfReduce = SelfReduce.DELVE)
                    l.startsWith("affinity for artifacts") -> o.copy(selfReduce = SelfReduce.AFFINITY)
                    l == "undaunted" -> o.copy(selfReduce = SelfReduce.UNDAUNTED)
                    l.startsWith("squad") -> o.copy(copyCost = costAfter(l, "squad"), copyMax = 3)
                    l.startsWith("offspring") -> o.copy(copyCost = costAfter(l, "offspring"), copyMax = 1)
                    l.startsWith("replicate") -> o.copy(copyCost = costAfter(l, "replicate"), copyMax = 2)
                    l == "demonstrate" -> o.copy(demonstrate = true)
                    l == "storm" -> o.copy(storm = true)
                    l.startsWith("buyback") -> o.copy(buyback = costAfter(l, "buyback"))
                    l.startsWith("crew ") -> o.copy(crew = rx("""crew (\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                    l.startsWith("you may cast ~ from your graveyard") -> o.copy(gyCost = cost, gyMode = GyMode.RECAST)
                    l.startsWith("suspend ") -> rx("""suspend (\d+)—((?:\{[^}]+\})+)""").find(l)?.let { m ->
                        o.copy(suspend = ManaCost.parse(m.groupValues[2]), suspendTurns = m.groupValues[1].toInt())
                    } ?: o
                    else -> rx("""this spell costs \{(\d+)\} less to cast for each ([^.]+)""").find(l)?.let { m ->
                        EffectParser.scale("for each " + m.groupValues[2])?.let { sc -> o.copy(selfReduce = SelfReduce.PER_SCALE, reducePer = m.groupValues[1].toInt(), reduceScale = sc) }
                    } ?: o
                }
            }
            return o
        }

        private val gyWords = mapOf(
            "flashback" to GyMode.FLASHBACK, "retrace" to GyMode.RETRACE, "jump-start" to GyMode.JUMP_START, "escape" to GyMode.ESCAPE,
            "unearth" to GyMode.UNEARTH, "embalm" to GyMode.EMBALM, "eternalize" to GyMode.ETERNALIZE, "encore" to GyMode.ENCORE,
            "disturb" to GyMode.DISTURB, "harmonize" to GyMode.FLASHBACK,
        )
    }
}

/** What a tutor can find and where it puts it. [types] null = any card. */
data class TutorSpec(val types: Set<CardType>?, val maxMv: Int?, val dest: TutorDest) {
    fun allows(c: SimCard) = !c.isLand && (types == null || c.type in types || (CardType.CREATURE in types && c.isCreature)) &&
        (maxMv == null || c.mv <= maxMv)
}

/** One card (or token), as the quick model plays it, built from the deck's Scryfall data. */
class SimCard(
    val name: String,
    val type: CardType,
    val cost: ManaCost,
    val mv: Int,
    val roles: Set<Role>,
    /** Colour bits this card can produce as a mana source (lands, rocks, dorks). */
    val produces: Int,
    val entersTapped: Boolean,
    val power: Int,
    val toughness: Int,
    /** [Kw] bits. */
    val keywords: Int,
    /** "+N/+N to (other) creatures you control", or to one creature type when [lordType] is set. */
    val anthem: Int,
    /** Creature type an anthem is limited to (a "lord"), lowercase singular; null = every creature. */
    val lordType: String? = null,
    /** "+N/+N to one creature" (equipment, auras). */
    val buff: Int,
    /** Cards drawn when cast (DRAW), per turn (ENGINE), when no parsed ability says otherwise. */
    val draws: Int,
    /** Damage to an opponent when cast (BURN) or to each opponent (DRAIN), when not parsed. */
    val damage: Int,
    /** Tokens' total power when cast (TOKENS), when not parsed. */
    val tokenPower: Int,
    val isCommander: Boolean,
    /** Creature types, lowercase ("halfling", "advisor"). */
    val subtypes: Set<String> = emptySet(),
    val removalKind: RemovalKind = RemovalKind.DESTROY,
    /** Damage dealt to the target by damage-based removal. */
    val removalDamage: Int = 0,
    val recursionToBattlefield: Boolean = false,
    val abilities: List<Ability> = emptyList(),
    val activated: List<Activated> = emptyList(),
    val coverage: Coverage = Coverage(emptyList(), emptyList()),
    /** For tokens: the card that made it. */
    val madeBy: String? = null,
    /** Mana each use makes ("{T}: Add {C}{C}" = 2), and a "for each" multiplier (Gaea's Cradle). */
    val manaAmount: Int = 1,
    val manaScale: Scale? = null,
    /** Mana an activated mana ability costs to use (Cabal Coffers' {2}). */
    val manaActivation: Int = 0,
    val tutor: TutorSpec? = null,
    /** Combat trick: "+N/+N until end of turn". */
    val pump: Int = 0,
    /** Equip cost (null = not equipment). */
    val equipCost: Int? = null,
    /** Keywords an equipment or aura gives. */
    val grants: Int = 0,
    val isAura: Boolean = false,
    /** "… spells you cost {N} less": which spells ("creature", "artifact", "noncreature", "instant", or "" for all) and N. */
    val reduceFor: String? = null,
    val reduceBy: Int = 0,
    /** Poison counters dealt with combat damage to a player (toxic N). */
    val toxic: Int = 0,
    val loyalty: List<Loyalty> = emptyList(),
    val chapters: List<List<Effect>> = emptyList(),
    /** Keywords given to every creature you control ("other permanents you control have hexproof"). */
    val teamGrants: Int = 0,
    /** [teamGrants] are for "other" creatures only (not this one). */
    val teamGrantsOthers: Boolean = false,
    /** Token doublers multiply tokens you create (Parallel Lives = 2). */
    val tokenMultiplier: Int = 1,
    /** "Those tokens plus that many 1/1 Squirrels" (Chatterfang). */
    val extraSquirrels: Boolean = false,
    /** Keywords a lord gives its creature type ("other Merfolk get +1/+1 and have islandwalk"). */
    val lordGrants: Int = 0,
    /** Landwalk colours this creature has (islandwalk = blue): unblockable against players with such a land. */
    val landwalk: Int = 0,
    val lordLandwalk: Int = 0,
    /** +1/+1 counter modifiers: Hardened Scales (+1), Branching Evolution / Doubling Season (×2). */
    val counterPlus: Int = 0,
    val counterMultiplier: Int = 1,
    /** "Explores, then explores again." */
    val exploreTwice: Boolean = false,
    /** Extra mana each land makes when tapped (Mirari's Wake, Zendikar Resurgent). */
    val landManaExtra: Int = 0,
    /** Number of cascade instances. */
    val cascade: Int = 0,
    /** For an earthbent land: the land card it returns as. */
    val earthbentFrom: SimCard? = null,
    /** What this card's removal can hit ("creature", "artifact or enchantment", "permanent"…). */
    val removalWhat: String = "permanent",
    /** "Power and toughness are each equal to the number of …" (added to the printed P/T). */
    val ptScale: Scale? = null,
    /** Chromatic Lantern: lands make any of the deck's colours. */
    val landsAnyColor: Boolean = false,
    /** Abundant Growth: one land makes any colour. */
    val fixesOneLand: Boolean = false,
    /** Wild Growth / Utopia Sprawl: extra mana each turn. */
    val manaBonus: Int = 0,
    val uncounterable: Boolean = false,
    val noMaxHand: Boolean = false,
    val extraCost: ExtraCost? = null,
    /** "{B}: Regenerate ~": the cost, paid when it would be destroyed. */
    val regenCost: ManaCost? = null,
    /** Echo: pay this again on the next turn or sacrifice it. */
    val echo: ManaCost? = null,
    /** Persist (-1) or undying (+1): comes back once when it dies. */
    val returnsOnDeath: Int = 0,
    val cast: CastOptions = CastOptions.NONE,
    /** A curse: an aura that enchants a player. */
    val cursesPlayer: Boolean = false,
    val statics: Statics = Statics.NONE,
) {
    /** Bit per [Trigger] this card has an ability for, so the game can skip cards a trigger doesn't concern. */
    val triggerMask: Long = abilities.fold(0L) { m, a -> m or (1L shl a.trigger.ordinal) }
    val isLand: Boolean get() = Role.LAND in roles
    val isManaSource: Boolean get() = produces != 0 && (Role.LAND in roles || Role.ROCK in roles || Role.DORK in roles)
    val isCreature: Boolean get() = Role.CREATURE in roles
    val isToken: Boolean get() = madeBy != null
    val isArtifact: Boolean get() = type == CardType.ARTIFACT || Role.ROCK in roles
    val isPermanent: Boolean get() = type != CardType.INSTANT && type != CardType.SORCERY
    val isPlaneswalker: Boolean get() = type == CardType.PLANESWALKER
    val isEquipment: Boolean get() = equipCost != null

    fun has(bit: Int) = keywords and bit != 0
    val flying get() = has(Kw.FLYING)
    val trample get() = has(Kw.TRAMPLE)
    val haste get() = has(Kw.HASTE)
    val deathtouch get() = has(Kw.DEATHTOUCH)
    val lifelink get() = has(Kw.LIFELINK)
    val unblockable get() = has(Kw.UNBLOCKABLE)
    val reach get() = has(Kw.REACH)
    val doubleStrike get() = has(Kw.DOUBLE_STRIKE)
    val firstStrike get() = has(Kw.FIRST_STRIKE) || has(Kw.DOUBLE_STRIKE)
    val menace get() = has(Kw.MENACE)

    /** This creature as copied by [via]: a token copy, or the clone card itself. Never a commander. */
    fun copiedBy(via: SimCard, token: Boolean, asPower: Int = 0, asToughness: Int = 0, extraType: String? = null): SimCard = SimCard(
        name = if (token) "$name token copy (${via.name})" else "${via.name} (as $name)",
        type = CardType.CREATURE.takeIf { asPower > 0 } ?: type, cost = cost, mv = mv, roles = roles, produces = produces, entersTapped = false,
        power = if (asPower > 0) asPower else power, toughness = if (asToughness > 0) asToughness else toughness,
        keywords = keywords, anthem = anthem, lordType = lordType, buff = buff, draws = draws, damage = damage, tokenPower = tokenPower,
        isCommander = false, subtypes = subtypes + listOfNotNull(extraType), removalKind = removalKind, removalDamage = removalDamage,
        recursionToBattlefield = recursionToBattlefield,
        // A copy keeps everything except abilities that would copy again.
        abilities = abilities.filter { a -> a.effects.none { it is CopyCreature || it == CopySelf } }, activated = activated, coverage = coverage,
        madeBy = if (token) via.name else null,
        manaAmount = manaAmount, manaScale = manaScale, manaActivation = manaActivation, tutor = tutor, pump = pump, equipCost = equipCost,
        grants = grants, isAura = isAura, reduceFor = reduceFor, reduceBy = reduceBy, toxic = toxic, loyalty = loyalty, chapters = chapters,
        teamGrants = teamGrants, teamGrantsOthers = teamGrantsOthers, tokenMultiplier = tokenMultiplier, extraSquirrels = extraSquirrels,
        lordGrants = lordGrants, landwalk = landwalk, lordLandwalk = lordLandwalk, counterPlus = counterPlus, counterMultiplier = counterMultiplier,
        exploreTwice = exploreTwice, landManaExtra = landManaExtra, cascade = cascade, removalWhat = removalWhat, ptScale = ptScale,
        landsAnyColor = landsAnyColor, fixesOneLand = fixesOneLand, manaBonus = manaBonus, uncounterable = uncounterable, noMaxHand = noMaxHand,
        regenCost = regenCost, returnsOnDeath = if (token) 0 else returnsOnDeath, cast = cast, statics = statics,
    )

    /** This land animated by earthbend: a 0/0 haste creature that still taps for mana. */
    fun earthbent(): SimCard = SimCard(
        name = "$name (earthbent)", type = CardType.CREATURE, cost = ManaCost.FREE, mv = 0, roles = setOf(Role.CREATURE, Role.DORK),
        produces = produces, entersTapped = false, power = 0, toughness = 0, keywords = Kw.HASTE, anthem = 0, buff = 0, draws = 0,
        damage = 0, tokenPower = 0, isCommander = false, subtypes = setOf("land"), earthbentFrom = this,
    )

    /** True when some parsed ability or activation has an effect of this type. */
    fun parses(kind: Class<out Effect>): Boolean =
        abilities.any { a -> a.effects.any { kind.isInstance(it) } } || activated.any { a -> a.effects.any { kind.isInstance(it) } }

    /** Plain-language list of what the model does with this card. */
    fun describe(): List<String> = buildList {
        if (isCreature) add("${power}/${toughness} creature" + Kw.names(keywords).takeIf { it.isNotEmpty() }?.joinToString(", ", " · ").orEmpty())
        if (isManaSource && !isLand) add("mana: ${ManaColor.letters(produces).ifEmpty { "C" }}")
        if (anthem > 0) add("+$anthem/+$anthem to ${lordType?.let { "${it}s" } ?: "creatures"} you control")
        if (teamGrants != 0) add("your creatures have " + Kw.names(teamGrants).joinToString(", "))
        if (tokenMultiplier > 1) add("token doubler (×$tokenMultiplier)")
        if (extraSquirrels) add("each token you make brings a 1/1 Squirrel")
        if (lordGrants != 0 || lordLandwalk != 0) add("${lordType ?: "creature"}s get " + (Kw.names(lordGrants) + (if (lordLandwalk != 0) listOf("landwalk") else emptyList())).joinToString(", "))
        if (landwalk != 0) add("landwalk (${ManaColor.letters(landwalk)})")
        if (counterPlus > 0) add("+1/+1 counters: one extra")
        if (counterMultiplier > 1) add("+1/+1 counters: ×$counterMultiplier")
        if (exploreTwice) add("explore twice")
        if (landManaExtra > 0) add("lands make +$landManaExtra mana")
        if (cascade > 0) add("cascade ×$cascade")
        addAll(cast.describe())
        ptScale?.let { add("power/toughness +1 for each ${it.kind.label}") }
        regenCost?.let { add("regenerate (${it.total})") }
        echo?.let { add("echo ${it.total}") }
        if (returnsOnDeath < 0) add("persist") else if (returnsOnDeath > 0) add("undying")
        extraCost?.let { add("additional cost: ${it.name.lowercase().replace('_', ' ')}") }
        if (landsAnyColor) add("lands make any colour")
        if (fixesOneLand) add("one land makes any colour")
        if (manaBonus > 0) add("+$manaBonus mana a turn")
        if (uncounterable) add("can't be countered")
        addAll(statics.describe())
        if (buff > 0) add("+$buff power to one creature")
        if (manaAmount > 1 || manaScale != null) add("makes ${manaAmount}${manaScale?.describe().orEmpty()} mana" + if (manaActivation > 0) " (costs $manaActivation)" else "")
        roles.filter { it in setOf(Role.RAMP_LAND, Role.REMOVAL, Role.WIPE, Role.COUNTER, Role.PROTECTION, Role.RECURSION) }
            .forEach { add(it.name.lowercase().replace('_', ' ') + if (it == Role.REMOVAL) " (${removalKind.name.lowercase()})" else "") }
        tutor?.let { t -> add("tutor: ${t.types?.joinToString("/") { it.label.lowercase() } ?: "any card"}${t.maxMv?.let { " (MV ≤ $it)" }.orEmpty()} → ${t.dest.name.lowercase()}") }
        if (pump > 0) add("combat trick +$pump/+$pump")
        equipCost?.let { add("equip $it: +$buff" + Kw.names(grants).takeIf { g -> g.isNotEmpty() }?.joinToString(", ", ", ").orEmpty()) }
        if (isAura && buff > 0) add("aura: +$buff" + Kw.names(grants).takeIf { g -> g.isNotEmpty() }?.joinToString(", ", ", ").orEmpty())
        if (reduceBy > 0) add("${reduceFor?.ifBlank { null }?.let { "$it " } ?: ""}spells cost $reduceBy less")
        if (toxic > 0) add("toxic $toxic")
        if (has(Kw.INFECT)) add("infect")
        abilities.forEach { add(it.describe()) }
        activated.forEach { add(it.describe()) }
        loyalty.forEach { add(it.describe()) }
        chapters.forEachIndexed { i, e -> add("chapter ${i + 1}: " + e.joinToString("; ") { it.describe() }) }
    }

    companion object {
        private val numberWords = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "x" to 3,
        )
        private fun countAfter(text: String, pattern: Regex): Int? =
            pattern.find(text)?.groupValues?.getOrNull(1)?.lowercase()?.let { numberWords[it] ?: it.toIntOrNull() }

        private val wipeRx = Regex("(destroy|exile) all |(destroy|exile) each |(all|each) (other )?creatures? gets? -(\\d+|x)/-(\\d+|x)|creatures (your opponents control|you don't control) get -(\\d+|x)|return all nonland permanents|damage to each (other )?creature|return all (nontoken )?creatures?( tokens)? to their owners'? hands|sacrifices? the rest|destroy the rest|sacrifices all (permanents|creatures|nonland)")
        private val removalRx = Regex("(destroy|exile) (target|up to (one|two) target)(?![a-z ]*card)|deals? (\\d+|x) damage to (any target|target creature)|return target (nonland )?(creature|permanent|artifact|enchantment)[a-z ,]* to its owner's hand|target (creature|player|permanent)'s owner shuffles|owner of target [a-z ]+ shuffles it|target creature( an opponent controls| you don't control)? gets -(\\d+|x)/-(\\d+|x)|fights (target|up to one target|another target) creature|enchanted creature can't attack or block|enchanted (creature|permanent) (doesn't untap|loses all abilities)|enchanted creature (is an?|becomes an?) [a-z ]+ with base power and toughness|put target (attacking |blocking )?creature on the (top|bottom) of its owner's library")
        private val counterRx = Regex("counter target [a-z ,]*?spell|counter target activated|counter target ability")
        private val rampRx = Regex("search your library for (up to (one|two|three) )?(a |an )?(basic )?lands? cards?.{0,80}(onto|put (it|them) onto) the battlefield")
        private val tutorRx = Regex("search your library for (a|an|up to one) ([a-z, ]*?)\\bcards?\\b")
        private fun tutorSpec(text: String): TutorSpec? {
            val m = tutorRx.findAll(text).firstOrNull { "land" !in it.groupValues[2] && "basic" !in it.groupValues[2] } ?: return null
            val words = m.groupValues[2]
            val types = buildSet {
                if ("creature" in words) add(CardType.CREATURE)
                if ("artifact" in words || "equipment" in words) add(CardType.ARTIFACT)
                if ("enchantment" in words || "aura" in words) add(CardType.ENCHANTMENT)
                if ("instant" in words) add(CardType.INSTANT)
                if ("sorcery" in words) add(CardType.SORCERY)
                if ("planeswalker" in words) add(CardType.PLANESWALKER)
            }.ifEmpty { null }
            val after = text.substring(m.range.first)
            val maxMv = Regex("mana value (\\d+) or less").find(after.take(120))?.groupValues?.get(1)?.toIntOrNull()
            val dest = when {
                rx("(put|onto) (it|that card|them)? ?onto the battlefield|put it onto the battlefield").containsMatchIn(after.take(200)) -> TutorDest.BATTLEFIELD
                rx("(put|puts) (it|that card|them) into (your|its owner's) graveyard").containsMatchIn(after.take(200)) -> TutorDest.GRAVEYARD
                "on top of your library" in after.take(200) || "on top" in after.take(160) -> TutorDest.TOP
                else -> TutorDest.HAND
            }
            return TutorSpec(types, maxMv, dest)
        }
        private val pumpRx = Regex("target creature gets \\+(\\d+)/\\+(\\d+) until end of turn")
        private val reduceRx = Regex("(creature |artifact |noncreature |instant and sorcery |instant or sorcery |enchantment )?spells (?:you cast )?(?:of the chosen type |with [a-z0-9 ]+? |from anywhere other than your hand |that target [a-z ]+? )?(?:you cast )?cost \\{(\\d+)\\} less")
        /** Keywords it has only some of the time ("as long as it's monstrous", "during your turn"): treated as always on. */
        private val condKwRx = Regex("""^~ has ([a-z ,]+?) (?:as long as|unless)|^(?:as long as [^,]+|during (?:your turn|each of your turns)), (?:~|it) (?:has|gets \+\d+/\+\d+ and has) ([a-z ,]+?)(?: and is [^.]+)?\.?$""")
        /** Equipment that grants keywords only to some creatures ("as long as equipped creature is legendary, it has hexproof"). */
        private val condGrantRx = Regex("""^as long as equipped creature is [^,]+, it has ([a-z ,]+?)\.?$""")
        /** Keyword lords with a qualifier: "Other creatures you control with flying have indestructible", "All Sliver creatures have haste". */
        private val kwLordLineRx = Regex("""^(?:all |other )?(?:legendary |attacking |nontoken )?([a-z]+?)s?(?: and(?:/or)? [a-z]+?s?)? (?:creatures )?(?:you control )?(?:with (flying|counters on them|a counter on it|one or more counters on (?:it|them)) )?(?:get \+(\d+)/\+\d+ and )?ha(?:ve|s) ([a-z ,]+?)(?: \{\d+\})?\.?$""")
        private val walkWords = mapOf("islandwalk" to ManaColor.U, "forestwalk" to ManaColor.G, "swampwalk" to ManaColor.B, "mountainwalk" to ManaColor.R, "plainswalk" to ManaColor.W)
        private fun walks(text: String) = walkWords.entries.fold(0) { m, (w, c) -> if (w in text) m or c else m }
        val protectionRx = listOf(
            rx("""(creatures|permanents|artifacts|other creatures) you control (gain|have|get)[^.]{0,60}(hexproof|indestructible|shroud|protection|ward)"""),
            rx("""target (creature|permanent|artifact|commander)( or [a-z]+)?( you control)? (gains|has|gets)[^.]{0,60}(hexproof|indestructible|shroud|protection)"""),
            rx("""(equipped|enchanted) (creature|permanent) (has|gains|gets [^.]{0,20}and has)[^.]{0,40}(hexproof|indestructible|shroud|protection|ward)"""),
            rx("""phases? out"""),
            rx("""you have (hexproof|shroud)"""),
            rx("""counter target spell or ability an opponent controls that targets"""),
        )
        val recursionRx = listOf(
            rx("""return[^.]{0,80}from (your|a) graveyard to (your hand|the battlefield)"""),
            rx("""(put|return)[^.]{0,60}from (your|a|any) graveyard onto the battlefield"""),
            rx("""return (target|up to [a-z]+ target|all|each)[^.]{0,40}cards? from your graveyard"""),
        )
        private val lordRx = Regex("""(?:other ([a-z]+) (?:creatures )?(?:you control )?|([a-z]+) creature tokens you control |creature (tokens) you control )get \+(\d+)/\+\d+(?: and have ([a-z ,]+))?""")
        private fun singular(w: String): String = when {
            w.endsWith("ves") -> w.dropLast(3) + "f"
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w == "creatures" || w == "creature" -> "creature"
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
        private val anthemRx = Regex("""(other )?creatures you control get \+(\d+)/\+\d+""")
        /** Lords without "other": "Zombies you control get +1/+1", "All Sliver creatures get +1/+1", "creatures you control with flying get +1/+1". */
        private val lordRx2 = Regex("""(?:^|\n)(?:all |other )?([a-z]+?)s? (?:creatures )?(?:tokens )?(?:you control )?(?:with (flying) )?get \+(\d+)/\+\d+(?: and have ([a-z ,]+))?""")
        private val chosenLordRx = Regex("""(other )?creatures (?:you control )?of the chosen type (?:you control )?get \+(\d+)/\+\d+""")
        /** "Creatures you control of the chosen color get +1/+1" (Caged Sun): the deck's own colour, so every creature. */
        private val chosenColorRx = Regex("""creatures you control of the chosen color get \+(\d+)/\+\d+""")
        private val staticRx = Regex(
            """lands you control (have "\{t\}: add|are every basic land type)|enchanted land (has|is) |whenever enchanted land is tapped for mana|can't be countered|""" +
                """no maximum hand size|power and toughness are each equal to|~'s power is equal to|~ gets \+1/\+1 for each|of the chosen type (you control )?get \+|""" +
                """creature you control with (a|one or more) \+1/\+1 counters? on it has|creature you control (of the chosen type )?enters with (an|a number of|two) additional|""" +
                """you gain that much life plus|can't gain life|are every creature type|may play an additional land|if you control a commander, you may cast this spell without paying|""" +
                """during each other player's untap step|double that damage|prevent half that damage|prevent all combat damage that would be dealt to (and dealt by )?~|""" +
                """creatures with flying can't attack you|creatures can't attack you[^.]*unless their controller pays|creatures with power less than [^.]+'s power can't block|""" +
                """instead create one of each|^you may cast ~ from your graveyard|you gain twice that much life instead|creatures your opponents control enter tapped|""" +
                """causes a triggered ability of a permanent you control to trigger, that ability triggers an additional time|is less than (five|seven), ~ isn't a creature|""" +
                """^skip your draw step|^~ has [a-z ,]+ as long as|^as long as [^,]+, ~ has [a-z ,]+\.?$|""" +
                """^(all |other )?[a-z]+s? (creatures )?(tokens )?(you control )?(with flying )?get \+\d+/\+\d+|^(all |other )?[a-z]+s? (creatures )?tokens (you control )?have """,
        )
        private val buffRx = Regex("""(equipped|enchanted) creature gets \+(\d+)/""")

        /** A token creature made by [maker]. */
        fun token(t: MakeTokens, maker: SimCard): SimCard = SimCard(
            name = "${t.name} token (${maker.name})", type = CardType.CREATURE, cost = ManaCost.FREE, mv = 0,
            roles = setOf(Role.CREATURE), produces = 0, entersTapped = false, power = t.power, toughness = maxOf(1, t.toughness),
            keywords = t.keywords, anthem = 0, buff = 0, draws = 0, damage = 0, tokenPower = 0, isCommander = false,
            subtypes = t.name.lowercase().split(' ').toSet(), madeBy = maker.name,
        )

        /**
         * Builds the model's view of a card. [deckColors] is the deck's colour identity, used for fetch lands and
         * "any colour" sources.
         */
        fun from(card: DeckCard, deckColors: Int, commander: Boolean = false): SimCard {
            // Double-faced cards: the front face is what gets played.
            val o = card.oracle.orEmpty().substringBefore("\n//\n")
            val text = o.lowercase()
            val type = card.type.takeIf { it != CardType.UNKNOWN } ?: when {
                card.name.lowercase() in DeckParser.basicLands -> CardType.LAND
                commander -> CardType.CREATURE
                else -> CardType.UNKNOWN
            }
            val typeLine = card.typeLine.orEmpty()
            val tags = card.tags.orEmpty().toSet()
            val mv = card.manaValue?.toInt() ?: 0
            val cost = if (type == CardType.LAND) ManaCost.FREE else ManaCost.parse(card.manaCost, mv)

            // Mana production: Scryfall's produced_mana, basics by name, fetch lands from their text.
            var produces = ManaColor.mask(card.produces)
            val basic = mapOf("plains" to ManaColor.W, "island" to ManaColor.U, "swamp" to ManaColor.B, "mountain" to ManaColor.R, "forest" to ManaColor.G)
            if (produces == 0 && type == CardType.LAND) {
                basic.entries.firstOrNull { card.name.lowercase().contains(it.key) }?.let { produces = it.value }
            }
            val isFetch = type == CardType.LAND && rx("search your library for an? (basic )?land|search your library for an? (\\w+ or \\w+|\\w+, \\w+,? or \\w+) card").containsMatchIn(text)
            if (isFetch) produces = produces or (deckColors.takeIf { it != 0 } ?: ManaColor.ALL)
            // "Any colour" sources (Command Tower, Arcane Signet) only matter for the deck's own colours.
            if (produces and ManaColor.ALL == ManaColor.ALL && deckColors and ManaColor.ALL != 0) produces = deckColors and ManaColor.ALL
            if ("any color" in text && produces == 0) produces = deckColors.takeIf { it != 0 } ?: ManaColor.ALL

            val entersTapped = type == CardType.LAND && (isFetch ||
                (rx("enters( the battlefield)? tapped").containsMatchIn(text) && "unless" !in text && "you may pay" !in text))

            val roles = mutableSetOf<Role>()
            if (type == CardType.LAND) roles += Role.LAND
            // Vehicles play as creatures that need a crew (see the combat code).
            val creature = "Creature" in typeLine.substringBefore("//") || (commander && type == CardType.CREATURE) ||
                ("Vehicle" in typeLine.substringBefore("//") && card.power?.toIntOrNull() != null)
            if (creature) roles += Role.CREATURE
            if (type != CardType.LAND && produces != 0) roles += if (creature) Role.DORK else Role.ROCK
            if (type != CardType.LAND && rampRx.containsMatchIn(text)) roles += Role.RAMP_LAND
            if ("ramp" in tags && type != CardType.LAND && roles.none { it == Role.ROCK || it == Role.DORK }) roles += Role.RAMP_LAND
            if (wipeRx.containsMatchIn(text) || "wipe" in tags) roles += Role.WIPE
            else if (removalRx.containsMatchIn(text) || "removal" in tags) roles += Role.REMOVAL
            if (counterRx.containsMatchIn(text) || "counter" in tags) roles += Role.COUNTER
            val drawsOnCast = countAfter(text, rx("draws? (a|an|one|two|three|four|five|six|seven|x|\\d+) cards?"))
            val engine = Regex("(at the beginning of your (upkeep|draw step|end step)|whenever [^.]{0,80}),? (you may )?draw").containsMatchIn(text)
            if (engine) roles += Role.ENGINE else if (drawsOnCast != null || "draw" in tags) roles += Role.DRAW
            val tutor = tutorSpec(text)
            if (tutor != null || "tutor" in tags) roles += Role.TUTOR
            val pump = if (type == CardType.INSTANT) pumpRx.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
            if (pump > 0) roles += Role.TRICK
            val burn = countAfter(text, rx("deals? (\\d+|x) damage to (any target|target player|target opponent|each opponent)"))
            if (burn != null && "each opponent" in text) roles += Role.DRAIN
            else if (burn != null || "burn" in tags) roles += Role.BURN
            if (rx("each opponent loses (\\d+|x|one|two|three) life").containsMatchIn(text)) roles += Role.DRAIN
            if (rx("(?<!its controller )create (a|an|one|two|three|four|x|\\d+) [^.]{0,40}tokens?").containsMatchIn(text)) roles += Role.TOKENS
            if ("lifegain" in tags || rx("you gain (\\d+|x) life").containsMatchIn(text)) roles += Role.LIFEGAIN
            if ("protection" in tags || protectionRx.any { it.containsMatchIn(text) }) roles += Role.PROTECTION
            if ("recursion" in tags || recursionRx.any { it.containsMatchIn(text) }) roles += Role.RECURSION
            if (roles.isEmpty() || roles == setOf(Role.LIFEGAIN)) roles += Role.OTHER

            val kwText = card.keywords.orEmpty().joinToString(", ") { it.lowercase() }
            var keywords = Kw.of(kwText)
            val norm = EffectParser.normalise(card.name, o)
            val normLines = norm.split('\n').map { it.trim() }
            if (normLines.any { it == "horsemanship" || it == "shadow" }) keywords = keywords or Kw.UNBLOCKABLE
            if (normLines.any { it == "skulk" }) keywords = keywords or Kw.MENACE
            if (normLines.any { it == "decayed" }) keywords = keywords or Kw.CANT_BLOCK
            if (normLines.any { it == "fear" || it == "intimidate" }) keywords = keywords or Kw.FEAR
            if (normLines.any { it.startsWith("spells your opponents cast that target ~ cost") }) keywords = keywords or Kw.WARD
            // Keywords it has only some of the time ("as long as it's untapped") are treated as always on.
            normLines.forEach { l ->
                condKwRx.find(l)?.let { m -> keywords = keywords or Kw.of(m.groupValues[1] + " " + m.groupValues[2]) }
            }
            if (rx("""~ can't be blocked""").containsMatchIn(norm)) keywords = keywords or Kw.UNBLOCKABLE
            if (rx("""~ can't block""").containsMatchIn(norm)) keywords = keywords or Kw.CANT_BLOCK
            fun stat(v: String?) = v?.toIntOrNull() ?: if (v != null) maxOf(1, mv - 1) else 0
            val drainAmount = countAfter(text, rx("each opponent loses (\\d+|x|one|two|three) life"))
            val tokenCount = countAfter(text, rx("create (a|an|one|two|three|four|x|\\d+) [^.]{0,40}tokens?")) ?: 0
            val tokenPower = Regex("(\\d+)/(\\d+)[^.]{0,30}token").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            // Anthems only from always-on text: not "until end of turn", not activated or triggered abilities.
            val staticText = text.split('\n').filter { l ->
                "until end of turn" !in l && ':' !in l && !l.trimStart().startsWith("when") && !l.trimStart().startsWith("at the beginning")
            }.joinToString("\n")
            val lord = lordRx.find(staticText)?.takeIf { m -> m.groupValues[1].isEmpty() || singular(m.groupValues[1]) != "creature" }
            val chosenLord = chosenLordRx.find(staticText)
            val lord2 = if (lord != null || chosenLord != null) null else lordRx2.find(staticText)?.takeIf { m ->
                val w = singular(m.groupValues[1])
                (w != "creature" || m.groupValues[2].isNotEmpty()) && w !in setOf("other", "each", "target", "equipped", "enchanted", "it", "that", "attacking", "~")
            }
            val lordType = if (chosenLord != null) "chosen" else if (lord2 != null) (if (lord2.groupValues[2].isNotEmpty()) "flying" else singular(lord2.groupValues[1])) else lord?.let { m ->
                when {
                    m.groupValues[3].isNotEmpty() || m.groupValues[2].isNotEmpty() -> "token"
                    else -> singular(m.groupValues[1])
                }
            }
            // Keyword-only lords: "Horrors you control have menace", "All Rats have fear", "Other Merfolk have islandwalk".
            val kwLord = Regex("""(?:all |other )?([a-z]+?)s? (?:creatures )?(tokens )?(?:you control )?have ([a-z ,]+)""").find(staticText)
                ?.takeIf { m -> (m.groupValues[2].isNotEmpty() || m.groupValues[1] !in setOf("creature", "permanent", "land", "artifact", "it", "they", "spell", "you")) && '"' !in m.value }
            val lordGrantText = lord?.groupValues?.get(5)?.ifBlank { null } ?: lord2?.groupValues?.get(4)?.ifBlank { null } ?: kwLord?.groupValues?.get(3).orEmpty()
            val anthem = (chosenLord?.groupValues?.get(2) ?: lord?.groupValues?.get(4) ?: lord2?.groupValues?.get(3) ?: anthemRx.find(staticText)?.groupValues?.get(2) ?: chosenColorRx.find(staticText)?.groupValues?.get(1))?.toIntOrNull()?.coerceAtMost(3) ?: 0
            val teamMatch = Regex("""(other )?(creatures|permanents) you control have ([a-z ,]+)""").find(staticText)
            val teamGrants = teamMatch?.groupValues?.get(3)?.takeIf { '"' !in it }?.let { Kw.of(it) } ?: 0
            var teamGrantsOthers = teamMatch?.groupValues?.get(1)?.isNotEmpty() == true
            // Qualified keyword lords the patterns above miss.
            var qLordType: String? = null
            var qLordKw = 0
            var qAnthem = 0
            if (lordType == null && kwLord == null && anthem == 0 && teamGrants == 0) {
                staticText.split('\n').map { it.trim() }.firstNotNullOfOrNull { kwLordLineRx.find(EffectParser.normalise(card.name, it)) }?.let { m ->
                    val kw = Kw.of(m.groupValues[4])
                    val w = singular(m.groupValues[1])
                    if (kw != 0 && w !in setOf("it", "they", "you", "spell", "land", "permanent", "~", "equipped", "enchanted", "each", "target")) {
                        qLordType = when {
                            m.groupValues[2] == "flying" -> "flying"
                            m.groupValues[2].isNotEmpty() -> "countered"
                            w == "creature" -> null
                            w == "commander" -> "legendary"
                            else -> w
                        }
                        if (qLordType == null && w == "creature") teamGrantsOthers = m.value.startsWith("other")
                        qLordKw = kw
                        qAnthem = m.groupValues[3].toIntOrNull()?.coerceAtMost(2) ?: 0
                    }
                }
            }
            val tokenMultiplier = if (rx("""tokens? would be created under your control, twice that many|creates? twice that many of those tokens""").containsMatchIn(text)) 2 else 1
            val extraSquirrels = "plus that many 1/1 green squirrel" in text
            val counterMultiplier = if (rx("""twice that many (of those )?(\+1/\+1 )?counters""").containsMatchIn(text)) 2 else 1
            val counterPlus = if (rx("""that many plus one \+1/\+1 counters""").containsMatchIn(text)) 1 else 0
            val removalKind = when {
                rx("fights (target|up to one target|another target) creature").containsMatchIn(text) -> RemovalKind.FIGHT
                rx("target creature( an opponent controls| you don't control)? gets -\\d+/-\\d+").containsMatchIn(text) -> RemovalKind.MINUS
                rx("exile (target|all|each|up to)").containsMatchIn(text) -> RemovalKind.EXILE
                rx("return target [a-z ]+ to its owner's hand|return all [a-z ]*creatures[a-z ]* to their owners'? hands").containsMatchIn(text) -> RemovalKind.BOUNCE
                rx("deals? (\\d+|x) damage to (any target|target creature)").containsMatchIn(text) -> RemovalKind.DAMAGE
                else -> RemovalKind.DESTROY
            }
            val removalDamage = countAfter(text, rx("deals? (\\d+|x) damage to (any target|target creature)"))
                ?: rx("gets -(\\d+)/-(\\d+)").find(text)?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val subtypes = typeLine.substringBefore("//").substringAfter("—", "").lowercase().split(' ').filter { it.isNotBlank() }.toSet()

            // Which lines the role-based effects already cover (for the coverage report).
            fun roleLine(line: String): Boolean = wipeRx.containsMatchIn(line) || removalRx.containsMatchIn(line) ||
                counterRx.containsMatchIn(line) || rampRx.containsMatchIn(line) || tutorRx.containsMatchIn(line) ||
                anthemRx.containsMatchIn(line) || lordRx.containsMatchIn(line) || buffRx.containsMatchIn(line) ||
                protectionRx.any { it.containsMatchIn(line) } || recursionRx.any { it.containsMatchIn(line) } ||
                pumpRx.containsMatchIn(line) || reduceRx.containsMatchIn(line) || tutorRx.containsMatchIn(line) ||
                rx("""(equipped|enchanted) creature (gets|has)""").containsMatchIn(line) ||
                rx("""tokens? would be created under your control|twice that many|that many plus one|(creatures|permanents) you control have|explores again|tap a (land|permanent) for mana""").containsMatchIn(line) ||
                rx("""^(all |other )?[a-z]+s? (you control )?have [a-z ,]+\.?$|you may look at the top card of your library""").containsMatchIn(line) ||
                rx("""~ can't (be blocked|block)|add \{|enters tapped|enters the battlefield tapped|^as ~ enters|for each|you may play|can't be countered""").containsMatchIn(line) ||
                staticRx.containsMatchIn(line) || chosenColorRx.containsMatchIn(line) || condKwRx.containsMatchIn(line) || condGrantRx.containsMatchIn(line) ||
                (kwLordLineRx.containsMatchIn(line) && Kw.of(kwLordLineRx.find(line)!!.groupValues[4]) != 0 &&
                    kwLordLineRx.find(line)!!.groupValues[1] !in setOf("it", "they", "you", "spell", "land", "permanent", "~", "equipped", "enchanted", "each", "target"))
            val parsed = if (type == CardType.LAND) null else
                EffectParser.parse(card.name, card.oracle, permanent = type != CardType.INSTANT && type != CardType.SORCERY, roleLine = ::roleLine)

            // Mana: how much each use makes.
            val manaLine = Regex("""(\{[^:]*\{t\}[^:]*|\{t\}[^:]*): add ([^.]+)""").find(text)
            var manaAmount = 1
            var manaScale: Scale? = null
            var manaActivation = 0
            if (manaLine != null) {
                val what = manaLine.groupValues[2]
                manaAmount = when {
                    what.startsWith("two mana") -> 2
                    what.startsWith("three mana") -> 3
                    else -> rx("""\{[wubrgc]\}""").findAll(what.substringBefore(" or ")).count().coerceAtLeast(1)
                }
                if ("for each" in what || "equal to" in what) manaScale = EffectParser.scale(what)
                manaActivation = rx("""\{(\d+)\}""").find(manaLine.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val equip = Regex("""\bequip \{(\d+)\}""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (rx("""\bequip\b""").containsMatchIn(text) && "Equipment" in typeLine) 2 else null
            val isAura = "Aura" in typeLine
            val grantLine = Regex("""(equipped|enchanted) creature (?:gets [^.]*? and )?(?:has|gains) ([a-z, ]+)""").find(text)?.groupValues?.get(2).orEmpty() +
                normLines.firstNotNullOfOrNull { condGrantRx.find(it) }?.let { ", " + it.groupValues[1] }.orEmpty() +
                if (Regex("""^(umbra|totem) armor""", RegexOption.MULTILINE).containsMatchIn(text)) ", indestructible" else ""
            val reduce = reduceRx.find(text)
            val removalWhat = Regex("""(destroy|exile|return) (up to (one|two) )?target ((?:non[a-z]+ )*(?:creature|artifact|enchantment|planeswalker|permanent)(?:(?:, |,? or )(?:non[a-z]+ )*(?:creature|artifact|enchantment|planeswalker|permanent))*)""")
                .find(text)?.groupValues?.get(4)
                ?: if (rx("""damage to (another )?target creature|target creature[a-z ]* gets -|fights|enchanted creature""").containsMatchIn(text)) "creature" else "permanent"
            val ptLine = Regex("""~'s power and toughness are each equal to ([^.]+)|~'s power is equal to ([^.]+)|~ gets \+1/\+1 for each ([^.]+)""").find(norm)
            val ptScale = ptLine?.let { EffectParser.scale(if (it.groupValues[3].isNotEmpty()) "for each " + it.groupValues[3] else it.value) }
            val extraCost = Regex("""as an additional cost to cast this spell, (discard|sacrifice an? (creature|artifact|land)|pay (x|\d+) life)""").find(text)?.let { m ->
                when {
                    m.groupValues[1] == "discard" -> ExtraCost.DISCARD
                    m.groupValues[2] == "creature" -> ExtraCost.SAC_CREATURE
                    m.groupValues[2] == "artifact" -> ExtraCost.SAC_ARTIFACT
                    m.groupValues[2] == "land" -> ExtraCost.SAC_LAND
                    else -> ExtraCost.LIFE
                }
            }
            val lines = norm.split('\n').map { it.trim() }

            return SimCard(
                name = card.name, type = type, cost = cost, mv = maxOf(mv, cost.total), roles = roles,
                produces = produces, entersTapped = entersTapped,
                power = if (!creature) 0 else if (ptScale != null && card.power?.toIntOrNull() == null) 0 else stat(card.power).let { if (card.power == null) maxOf(1, mv - 1) else it },
                toughness = if (!creature) 0 else if (ptScale != null && card.toughness?.toIntOrNull() == null) 0 else stat(card.toughness).let { if (card.toughness == null) maxOf(1, mv) else it },
                keywords = keywords,
                anthem = if (anthem == 0) qAnthem else anthem, lordType = qLordType ?: lordType ?: kwLord?.let { m -> if (m.groupValues[2].isNotEmpty() && singular(m.groupValues[1]) == "creature") "token" else singular(m.groupValues[1]) },
                buff = buffRx.find(text)?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(6) ?: 0,
                draws = when {
                    engine -> 1
                    else -> (drawsOnCast ?: if (Role.DRAW in roles) 2 else 0).coerceIn(0, 5)
                },
                damage = (drainAmount ?: burn ?: 0).coerceIn(0, 10),
                tokenPower = tokenCount.coerceIn(0, 6) * tokenPower,
                isCommander = commander,
                subtypes = subtypes, removalKind = removalKind, removalDamage = removalDamage,
                recursionToBattlefield = rx("graveyard (onto|to) the battlefield").containsMatchIn(text),
                abilities = parsed?.abilities.orEmpty(), activated = parsed?.activated.orEmpty(),
                coverage = parsed?.coverage ?: Coverage(emptyList(), emptyList()),
                manaAmount = manaAmount, manaScale = manaScale, manaActivation = manaActivation,
                tutor = tutor, pump = pump, equipCost = equip, grants = Kw.of(grantLine), isAura = isAura,
                reduceFor = reduce?.let { if ("other than your hand" in it.value) "commander" else it.groupValues[1].trim() }, reduceBy = reduce?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                toxic = rx("""\btoxic (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                loyalty = parsed?.loyalty.orEmpty(), chapters = parsed?.chapters.orEmpty(),
                teamGrants = if (qLordType == null && qLordKw != 0) qLordKw else teamGrants, teamGrantsOthers = teamGrantsOthers, tokenMultiplier = tokenMultiplier, extraSquirrels = extraSquirrels,
                lordGrants = if (qLordType != null) qLordKw else Kw.of(lordGrantText), landwalk = walks(kwText), lordLandwalk = walks(lordGrantText),
                counterPlus = counterPlus, counterMultiplier = counterMultiplier,
                exploreTwice = rx("""explores, then (it )?explores again""").containsMatchIn(text),
                // "Cascade, cascade" (Maelstrom Wanderer) counts twice.
                cascade = text.lines().filter { it.trim().startsWith("cascade") }.sumOf { l -> rx("""\bcascade\b""").findAll(l).count() }
                    .let { k -> if (k == 0 && card.keywords.orEmpty().any { it.equals("cascade", true) }) 1 else k },
                landManaExtra = if (rx("""whenever you tap a (land|permanent) for mana, add (one|an additional) (additional )?mana""").containsMatchIn(text)) 1 else 0,
                removalWhat = removalWhat, ptScale = ptScale,
                landsAnyColor = rx("""lands you control (have "\{t\}: add one mana of any color|are every basic land type)""").containsMatchIn(text),
                fixesOneLand = rx("""enchanted land has "\{t\}: add (one mana of any color|two mana)""").containsMatchIn(text),
                manaBonus = if (rx("""whenever enchanted land is tapped for mana, its controller adds""").containsMatchIn(text)) 1 else 0,
                uncounterable = rx("""(this spell|~) can't be countered""").containsMatchIn(norm) || lines.any { it.startsWith("split second") },
                noMaxHand = "you have no maximum hand size" in text,
                extraCost = extraCost,
                regenCost = parsed?.activated?.firstOrNull { it.regenerate }?.cost,
                echo = lines.firstOrNull { it.startsWith("echo") }?.let { l -> ManaCost.parse(l.removePrefix("echo").trim()).takeIf { it.total > 0 } ?: cost },
                returnsOnDeath = when { lines.any { it == "persist" } -> -1; lines.any { it == "undying" } -> 1; else -> 0 },
                cast = CastOptions.from(lines, cost),
                cursesPlayer = lines.any { it.startsWith("enchant player") || it.startsWith("enchant opponent") },
                statics = Statics.from(norm),
            )
        }
    }
}
