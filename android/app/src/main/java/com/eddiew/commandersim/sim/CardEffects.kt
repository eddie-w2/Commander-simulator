package com.eddiew.commandersim.sim

/*
 * Rules-text reading for the quick model. Each card's text is read once, when a run starts, into triggered,
 * activated and loyalty abilities made of a few effect types. The game loop then only counts and compares numbers,
 * which keeps it fast. Text the reader doesn't understand is reported (see [Coverage]) rather than guessed at.
 */

private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

/** A pattern compiled once and reused: the rules-text reader tries hundreds of patterns on every card. */
internal fun rx(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

/** When a triggered ability fires. */
enum class Trigger(val label: String) {
    CAST("on cast"),
    ETB("enters"),
    SELF_DIES("it dies"),
    ATTACK("you attack"),
    SELF_ATTACK("it attacks"),
    COMBAT_DAMAGE("it deals combat damage to a player"),
    UPKEEP("your upkeep"),
    END_STEP("your end step"),
    LANDFALL("a land enters"),
    CAST_SPELL("you cast a spell"),
    CAST_NONCREATURE("you cast a noncreature spell"),
    ARTIFACT_ETB("an artifact enters"),
    CREATURE_ETB("a creature enters"),
    DIES("a creature dies"),
    OWN_DIES("a creature you control dies"),
    COMBAT_START("beginning of combat on your turn"),
    /** "Whenever another Cat you control attacks": once per attacking creature (of [Ability.tribe]). */
    CREATURE_ATTACKS("a creature you control attacks"),
    /** "Whenever a creature you control explores". */
    EXPLORES("a creature you control explores"),
    /** "Whenever a Merfolk you control deals combat damage to a player": once per creature that connects. */
    CREATURE_COMBAT_DAMAGE("a creature you control deals combat damage to a player"),
    DRAW("you draw a card"),
    OPP_CAST("an opponent casts a spell"),
    ANY_CAST("a player casts a spell"),
    /** Equipment and auras: the creature it's attached to attacks / dies / deals combat damage to a player. */
    ATTACHED_ATTACKS("equipped or enchanted creature attacks"),
    ATTACHED_DIES("equipped or enchanted creature dies"),
    ATTACHED_DAMAGE("equipped or enchanted creature deals combat damage"),
    LIFEGAIN("you gain life"),
    OPP_UPKEEP("each opponent's upkeep"),
    EACH_UPKEEP("each upkeep"),
    COUNTERS_PUT("+1/+1 counters are put on your creature"),
    /** An opponent sacrifices a nontoken permanent or discards a permanent card (Tergrid). */
    OPP_LOSES_CARD("an opponent sacrifices or discards a card"),
    /** "Whenever you sacrifice a permanent." */
    SACRIFICE("you sacrifice a permanent"),
    SACRIFICE_ANY("a player sacrifices a permanent"),
    SELF_DAMAGED("it is dealt damage"),
    SELF_BLOCKS("it blocks"),
    /** Curses: the enchanted player is attacked. */
    CURSE("enchanted player is attacked"),
    ENCHANTMENT_ETB("an enchantment enters"),
    /** You (or a planeswalker of yours) are attacked. */
    OPP_ATTACKS("a creature attacks you"),
    ANY_ATTACK("a player attacks"),
    CYCLED("you cycle it"),
    DISCARD("you discard a card"),
    MONSTROUS("it becomes monstrous"),
    SURVEIL("you surveil"),
    COMMANDER("your commander enters or attacks"),
    OPP_CREATURE_ETB("a creature an opponent controls enters"),
    LEVEL_UP("it gains a level"),
    TOKEN_CREATED("you create a token"),
    /** A repeatable event the model doesn't track ("whenever a card is put into an opponent's graveyard"): about every other turn. */
    SOMETIMES("≈ every other turn (event not tracked)"),
}

enum class TokenKind { CREATURE, TREASURE, FOOD, CLUE }

/** Keyword bits shared by cards and tokens. */
object Kw {
    const val FLYING = 1; const val TRAMPLE = 2; const val HASTE = 4; const val DEATHTOUCH = 8; const val LIFELINK = 16
    const val REACH = 32; const val FIRST_STRIKE = 64; const val DOUBLE_STRIKE = 128; const val VIGILANCE = 256; const val MENACE = 512
    const val INDESTRUCTIBLE = 1024; const val HEXPROOF = 2048; const val WARD = 4096; const val DEFENDER = 8192; const val CANT_BLOCK = 16384
    const val UNBLOCKABLE = 32768; const val INFECT = 65536; const val EXALTED = 131072; const val FEAR = 262144

    private val words = listOf(
        "flying" to FLYING, "trample" to TRAMPLE, "haste" to HASTE, "deathtouch" to DEATHTOUCH, "lifelink" to LIFELINK,
        "reach" to REACH, "first strike" to FIRST_STRIKE, "double strike" to DOUBLE_STRIKE, "vigilance" to VIGILANCE,
        "menace" to MENACE, "indestructible" to INDESTRUCTIBLE, "hexproof" to HEXPROOF, "shroud" to HEXPROOF, "ward" to WARD,
        "defender" to DEFENDER, "infect" to INFECT, "exalted" to EXALTED, "fear" to FEAR, "intimidate" to FEAR,
        // Protection is played as hexproof (it can't be targeted; blocking and damage aren't modelled separately).
        "protection" to HEXPROOF,
    )

    /** Bits for keywords named in [text] (a "with …" clause or Scryfall's keyword list). */
    fun of(text: String): Int = words.fold(0) { m, (w, bit) -> if (rx("\\b$w\\b").containsMatchIn(text)) m or bit else m }

    fun names(bits: Int): List<String> = words.filter { bits and it.second != 0 }.distinctBy { it.second }.map { it.first }
}

/** "X" in an effect: the X paid for the spell (or 2 when there's no X to pay). */
const val XN = -1
/** "That many" after combat damage: the damage just dealt. */
const val DMG = -2
/** "That many" after gaining life: life gained this turn. */
const val LIFE_GAINED = -3
/** "Damage equal to its power": the source's (or the triggering creature's) power. */
const val POWER = -4
/** "That much" after "whenever you gain life": the life just gained. */
const val LAST_GAIN = -5

enum class ScaleKind(val label: String) {
    CREATURES("creature you control"), ARTIFACTS("artifact you control"), LANDS("land you control"),
    LANDS_OF_COLOR("land of that type"), TRIBE("creature of that type"), OPPONENTS("opponent"), DEVOTION("devotion"),
    HAND("card in hand"), ENCHANTMENTS("enchantment you control"), TOKENS("token you control"),
    GREATEST_POWER("point of your greatest power"), OPP_CREATURES("creature your opponents control"),
    ALL_CREATURES("creature on the battlefield"), GRAVEYARD("card in your graveyard"), GRAVEYARD_CREATURES("creature card in your graveyard"),
    KICKED("time it was kicked"), PERMANENTS("permanent you control"), COUNTERS("+1/+1 counter on it"),
    SOURCE_POWER("point of its power"), SPELL_MV("mana value of your last spell"), GREATEST_MV("point of your highest mana value"),
}

/** "For each …" / "equal to …" multipliers, counted when the effect happens. */
data class Scale(val kind: ScaleKind, val color: Int = 0, val tribe: String? = null) {
    fun describe() = " for each ${tribe ?: kind.label}"
}

sealed interface Effect {
    fun describe(): String
}

private fun amount(n: Int) = when (n) { XN -> "X"; DMG -> "that many"; LIFE_GAINED -> "life gained this turn"; POWER -> "its power"; LAST_GAIN -> "the life gained"; else -> "$n" }

data class MakeTokens(
    val count: Int, val kind: TokenKind, val name: String = "", val power: Int = 0, val toughness: Int = 0, val keywords: Int = 0,
    val scale: Scale? = null,
) : Effect {
    override fun describe(): String = when (kind) {
        TokenKind.CREATURE -> "create ${amount(count)} $power/$toughness $name token${if (count != 1) "s" else ""}" +
            Kw.names(keywords).takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
        else -> "create ${amount(count)} ${kind.name.lowercase()}${if (count != 1) "s" else ""}"
    } + scale?.describe().orEmpty()
}

data class Draw(val n: Int, val scale: Scale? = null, val eachPlayer: Boolean = false) : Effect {
    override fun describe() = (if (eachPlayer) "each player draws " else "draw ") + amount(n) + scale?.describe().orEmpty()
}
data class LoseLife(val n: Int, val each: Boolean, val scale: Scale? = null, val gainBack: Boolean = false) : Effect {
    override fun describe() = (if (each) "each opponent" else "an opponent") + " loses ${amount(n)}" + scale?.describe().orEmpty() +
        if (gainBack) ", you gain what they lose" else ""
}

/** A copy of the best creature on the battlefield: a token, or the card itself entering as one (Clone). */
data class CopyCreature(val token: Boolean, val ownOnly: Boolean) : Effect {
    override fun describe() = (if (token) "create a token copy of " else "enter as a copy of ") + if (ownOnly) "your best creature" else "the best creature"
}
data class Damage(val n: Int, val each: Boolean, val scale: Scale? = null) : Effect {
    override fun describe() = "${amount(n)} damage to " + (if (each) "each opponent" else "an opponent") + scale?.describe().orEmpty()
}
data class GainLife(val n: Int, val scale: Scale? = null) : Effect { override fun describe() = "gain ${amount(n)} life" + scale?.describe().orEmpty() }
data class Counters(
    val n: Int, val all: Boolean, val self: Boolean, val scale: Scale? = null,
    /** Only creatures of this type (with [all]). */
    val tribe: String? = null,
    /** "On each of up to two target creatures": how many creatures. */
    val spread: Int = 1,
) : Effect {
    override fun describe() = "+1/+1 counter ×${amount(n)} on " + when {
        all -> "each ${tribe ?: "creature"} you control"; self -> "itself"; spread > 1 -> "$spread creatures"; else -> "a creature"
    } + scale?.describe().orEmpty()
}

/** Who an effect hits. */
enum class Who(val label: String) {
    EACH_OPPONENT("each opponent"), OPPONENT("an opponent"), EACH_PLAYER("each player"), DEFENDER("the defending player"), SELF("you"),
}

/** "Each opponent sacrifices a creature": [what] is the kind of permanent (creature, artifact, permanent…). */
data class Edict(val n: Int, val who: Who, val what: String) : Effect {
    override fun describe() = "${who.label} sacrifices ${amount(n)} $what"
}

/** Discarding: opponents lose their worst cards; [Who.SELF] is looting (after drawing). 99 = the whole hand. */
data class Discard(val n: Int, val who: Who) : Effect {
    override fun describe() = "${who.label} discard${if (who == Who.SELF || who == Who.EACH_PLAYER) "" else "s"} " + if (n >= 99) "their hand" else amount(n)
}

/** Scry or surveil N: unwanted cards go to the bottom (scry) or the graveyard (surveil). */
data class Scry(val n: Int, val surveil: Boolean) : Effect {
    override fun describe() = (if (surveil) "surveil " else "scry ") + amount(n)
}

/** Taps opponents' best creatures so they can't block (also goad). */
data class TapCreatures(val n: Int, val goad: Boolean = false) : Effect {
    override fun describe() = (if (goad) "goad " else "tap ") + amount(n) + " opposing creature" + if (n != 1) "s" else ""
}

/** Become the monarch (or take the initiative): draw a card each end step until an opponent hits you. */
data object Monarch : Effect { override fun describe() = "become the monarch" }
/** Venture into the dungeon: a small bonus about every other time. */
data object Venture : Effect { override fun describe() = "venture into the dungeon" }

/** Targeted removal inside an ability or spell: [what] is what it can hit ("creature", "artifact or enchantment"…). */
data class Remove(val kind: RemovalKind, val what: String, val damage: Int = 0, val count: Int = 1) : Effect {
    override fun describe() = "${kind.name.lowercase()} ${if (count > 1) "$count " else ""}target $what" + if (damage != 0) " (${amount(damage)})" else ""
}

/** Damage (or -N/-N) to every creature: [players] also hits each player; [oppOnly] spares your own. */
data class Sweep(val n: Int, val players: Boolean = false, val flyersSafe: Boolean = false, val oppOnly: Boolean = false, val scale: Scale? = null) : Effect {
    override fun describe() = "${amount(n)}${scale?.describe().orEmpty()} damage to each ${if (oppOnly) "opposing " else ""}creature" + (if (flyersSafe) " without flying" else "") +
        if (players) " and each player" else ""
}

/** Cast a card for free: from hand (mana value ≤ [maxMv]) or, for discover, from the top of the library. */
data class FreeCast(val maxMv: Int, val fromLibrary: Boolean, val commander: Boolean = false) : Effect {
    override fun describe() = if (commander) "cast your commander for free" else
        (if (fromLibrary) "discover " else "cast a spell free from hand, MV ≤ ") + if (maxMv >= 99) "any" else amount(maxMv)
}

/** Each player discards their hand and draws [n] (0 = as many as the most anyone discarded). */
data class Wheel(val n: Int) : Effect { override fun describe() = "each player discards their hand and draws ${if (n == 0) "that many" else amount(n)}" }

/** Rituals and mana triggers: [n] mana now. */
data class AddMana(val n: Int) : Effect { override fun describe() = "add ${amount(n)} mana" }

/** Gain control of an opposing creature or artifact, for good or (Threaten) until end of turn. */
data class Steal(val untilEndOfTurn: Boolean, val what: String) : Effect {
    override fun describe() = "gain control of a $what" + if (untilEndOfTurn) " until end of turn" else ""
}

/** Return a creature card (or [anyPermanent] card) from your graveyard to hand or the battlefield. */
data class Reanimate(val toBattlefield: Boolean, val anyPermanent: Boolean = false) : Effect {
    override fun describe() = "return a ${if (anyPermanent) "permanent" else "creature"} card from your graveyard to " + if (toBattlefield) "the battlefield" else "hand"
}

/** Basic lands from the library: onto the battlefield (tapped) and into hand. */
data class FetchLands(val toBattlefield: Int, val toHand: Int) : Effect {
    override fun describe() = listOfNotNull(
        "$toBattlefield land${if (toBattlefield != 1) "s" else ""} onto the battlefield".takeIf { toBattlefield > 0 },
        "$toHand land${if (toHand != 1) "s" else ""} to hand".takeIf { toHand > 0 },
    ).joinToString(" and ")
}

/** "Sacrifice ~" as part of a trigger (end-step sacrifice, temporary creatures). */
data object SacrificeSelf : Effect { override fun describe() = "sacrifice it" }

/** Living weapon / For Mirrodin!: a creature token that the equipment is attached to. */
data class Germ(val power: Int, val toughness: Int, val name: String) : Effect {
    override fun describe() = "create a $power/$toughness $name token and attach to it"
}

/** Myriad: copies attack the other opponents (their unblocked damage). */
data object Myriad : Effect { override fun describe() = "myriad: hits the other opponents too" }

/** Devour N: eats small tokens for N counters each. */
data class Devour(val n: Int) : Effect { override fun describe() = "devour $n" }

/** Tergrid: the card an opponent just sacrificed or discarded comes to your side. */
data object TakeThatCard : Effect { override fun describe() = "put that card onto the battlefield under your control" }

/** Populate: a copy of your best creature token. */
data object Populate : Effect { override fun describe() = "populate" }

/** A token copy of this creature (squad, embalm-style effects, "create a token that's a copy of ~"). */
data object CopySelf : Effect { override fun describe() = "create a token copy of itself" }

/** Fog: prevents a combat's damage (used when attacked). */
data object Fog : Effect { override fun describe() = "prevent combat damage this turn" }

/** Energy: energy costs are treated as paid in this model, so getting energy changes nothing. */
data object Energy : Effect { override fun describe() = "get energy (energy costs count as paid)" }

/** "That player draws an additional card" on each player's turn: the player whose turn it is draws. */
data class ActiveDraws(val n: Int) : Effect { override fun describe() = "the player whose turn it is draws $n" }

/** Equipment attaching itself for free ("you may attach ~ to it"). */
data object Attach : Effect { override fun describe() = "attach to that creature" }

/** O-Ring-style "return the exiled card" when it leaves: the removal is treated as permanent here. */
data object ExileEnds : Effect { override fun describe() = "(the exiled card stays exiled in this model)" }

data object DoubleCounters : Effect { override fun describe() = "double the +1/+1 counters on your creatures" }

/** Copy the instant or sorcery you cast last (its read effects happen again). */
data object CopySpell : Effect { override fun describe() = "copy your last instant or sorcery" }

/** Look at the top card: if it's a [match] card it goes onto the battlefield (or hand); otherwise maybe to hand. */
data class TopCard(val match: String, val toBattlefield: Boolean, val elseHand: Boolean) : Effect {
    override fun describe() = "top card: a $match goes to ${if (toBattlefield) "the battlefield" else "hand"}" + if (elseHand) ", anything else to hand" else ""
}

data object HalfLife : Effect { override fun describe() = "that player loses half their life" }

data class Monstrosity(val n: Int) : Effect { override fun describe() = "monstrosity ${amount(n)}" }

/** A token copy of your best permanent of a kind ("create a token that's a copy of target artifact"). */
data class CopyPermanent(val what: String) : Effect { override fun describe() = "create a token copy of your best $what" }

/** Classes: gain level [n] (abilities of that level and below then apply). */
data class ClassLevel(val n: Int) : Effect { override fun describe() = "gain level $n" }

/** Untap: "self", "land" (more mana now), "creature" (all yours), "target", "all". */
data class Untap(val what: String, val n: Int) : Effect { override fun describe() = "untap " + when (what) { "land" -> "$n land(s)"; "self" -> "itself"; else -> what } }

/** Charge, quest, hour… counters on itself (spent by abilities that remove them). */
data class Mark(val n: Int) : Effect { override fun describe() = "put ${amount(n)} counter(s) on itself" }

/** Living Death / Open the Vaults: everyone's graveyard comes back (creatures die first for Living Death). */
data class MassReanimate(val noncreature: Boolean) : Effect {
    override fun describe() = if (noncreature) "return all artifact and enchantment cards from all graveyards" else "creatures die; all creature cards in graveyards return"
}

/** Cast an instant or sorcery from a graveyard for free (Goblin Dark-Dwellers). */
data class GraveCast(val maxMv: Int) : Effect { override fun describe() = "cast an instant or sorcery from a graveyard for free" + if (maxMv < 99) " (MV ≤ $maxMv)" else "" }

/** Exile the best creature card from opponents' graveyards. */
data object GraveHate : Effect { override fun describe() = "exile a card from an opponent's graveyard" }

enum class MillTarget { SELF, OPPONENT, EACH_OPPONENT, DAMAGED }
data class Mill(val n: Int, val target: MillTarget) : Effect {
    override fun describe() = when (target) {
        MillTarget.SELF -> "mill yourself"; MillTarget.OPPONENT -> "mill an opponent"; MillTarget.DAMAGED -> "that player mills"; else -> "mill each opponent"
    } + " ${amount(n)}"
}
/** "When ~ dies, return it to its owner's hand." */
data object ReturnToHand : Effect { override fun describe() = "returns to hand" }
/** Put the best artifact or creature card from an opponent's graveyard onto the battlefield under your control. */
data object StealFromGraveyard : Effect { override fun describe() = "steal a creature/artifact from an opponent's graveyard" }
/** Exile a creature card from any graveyard and make a token copy of it (The Scarab God: a 4/4 Zombie). */
data class GraveyardCopy(val power: Int, val toughness: Int, val tribe: String?) : Effect {
    override fun describe() = "token copy of a creature card from a graveyard" + if (power > 0) " as a $power/$toughness" else ""
}

/** Earthbend N: a land becomes a 0/0 haste creature with N +1/+1 counters (and returns when it dies). */
data class Earthbend(val n: Int) : Effect { override fun describe() = "earthbend ${amount(n)}" }

data class Poison(val n: Int, val each: Boolean) : Effect { override fun describe() = "${amount(n)} poison to " + if (each) "each opponent" else "an opponent" }
data object Proliferate : Effect { override fun describe() = "proliferate" }
data object ExtraTurn : Effect { override fun describe() = "take an extra turn" }
data object ExtraCombat : Effect { override fun describe() = "an extra combat" }

enum class WinCondition { LIFE, EMPTY_LIBRARY, ORACLE }
data class WinGame(val condition: WinCondition, val n: Int = 0) : Effect {
    override fun describe() = when (condition) {
        WinCondition.LIFE -> "win at $n+ life"
        WinCondition.EMPTY_LIBRARY -> "win when drawing from an empty library"
        WinCondition.ORACLE -> "win if your library is small enough"
    }
}

data class Ability(
    val trigger: Trigger,
    val effects: List<Effect>,
    val oncePerTurn: Boolean = false,
    /** Eminence: also works while the commander is in the command zone. */
    val fromCommandZone: Boolean = false,
    /** "You may pay {cost}. If you do, …" */
    val optionalCost: ManaCost? = null,
    /** Creature type the trigger is limited to ("another Cat you control attacks"). */
    val tribe: String? = null,
    /** "A creature with power N or greater / N or less" (0 = any). */
    val minPower: Int = 0,
    val maxPower: Int = 0,
    /** Evolve: only when the entering creature is bigger than this one. */
    val evolve: Boolean = false,
    /** Only when kicked (the "if it was kicked" line). */
    val kicked: Boolean = false,
    /** Classes: the level this ability belongs to (1 = from the start). */
    val minLevel: Int = 1,
) {
    fun describe() = (if (fromCommandZone) "eminence · " else "") + (if (kicked) "if kicked · " else "") + trigger.label +
        (tribe?.let { " ($it)" } ?: "") + (if (minPower > 0) " (power ≥ $minPower)" else "") + (if (maxPower > 0) " (power ≤ $maxPower)" else "") +
        (if (evolve) " (bigger)" else "") + (if (oncePerTurn) " (once a turn)" else "") +
        (optionalCost?.let { " · pay ${it.total}" } ?: "") + ": " + effects.joinToString("; ") { it.describe() }
}

enum class PumpTarget { SELF, ANOTHER, ALL, TRIGGERING }

/** "+N/+N until end of turn" (and keywords), on itself, another creature (of a type), all your creatures, or the attacker. */
data class Pump(
    val power: Int, val toughness: Int, val target: PumpTarget, val tribe: String? = null, val keywords: Int = 0,
    /** "+X/+X where X is its power". */
    val doublePower: Boolean = false,
    /** "+X/+X where X is …": [power] is [XN] and X is counted when it happens. */
    val scale: Scale? = null,
) : Effect {
    override fun describe() = (if (doublePower) "double power" else if (power == XN) "+X/+X" else "+$power/+$toughness") + scale?.describe().orEmpty() +
        Kw.names(keywords).takeIf { it.isNotEmpty() }?.joinToString(", ", " and ").orEmpty() + " to " +
        when (target) { PumpTarget.SELF -> "itself"; PumpTarget.ANOTHER -> "another ${tribe ?: "creature"}"; PumpTarget.ALL -> "your ${tribe ?: "creature"}s"; PumpTarget.TRIGGERING -> "that creature" } +
        " this turn"
}

enum class ExploreWho { SELF, TRIBE, ALL, TRIGGERING }
data class Explore(val who: ExploreWho, val tribe: String? = null) : Effect {
    override fun describe() = when (who) { ExploreWho.SELF -> "it explores"; ExploreWho.TRIBE -> "each $tribe explores"; ExploreWho.ALL -> "each creature explores"; ExploreWho.TRIGGERING -> "that creature explores" }
}

/** "You may put a land card from your hand onto the battlefield" (and "if you don't, draw a card"). */
data class LandFromHand(val elseDraw: Boolean) : Effect {
    override fun describe() = "put a land from hand onto the battlefield" + if (elseDraw) ", or draw a card" else ""
}

/** Casting from the top of the library (about an extra card every other turn). */
data object TopOfLibrary : Effect { override fun describe() = "cast from the top of your library (≈ +½ card a turn)" }

/** Keyword counters (flying, menace, shield…): the keyword for good, on itself or your best creature. Shield counters play as indestructible. */
data class KeywordCounter(val keywords: Int, val self: Boolean) : Effect {
    override fun describe() = "${Kw.names(keywords).joinToString("/")} counter on " + if (self) "itself" else "a creature"
}

/** Tokens an opponent gets ("target opponent creates three 2/2 Knight tokens"). */
data class OppTokens(val tokens: MakeTokens, val each: Boolean) : Effect {
    override fun describe() = (if (each) "each opponent: " else "an opponent: ") + tokens.describe()
}

/** "You lose 4 life", "~ deals 4 damage to you". */
data class LoseLifeSelf(val n: Int) : Effect { override fun describe() = "you lose ${amount(n)} life" }

/** Read, and makes no difference in this model (choosing an opponent, returning one of your own creatures to hand…). */
data class Minor(val what: String) : Effect { override fun describe() = "$what (no effect in this model)" }

/** "Return that card to the battlefield" after it died: the creature that just died comes back (with [keywords] from counters). */
data class ReturnDied(val keywords: Int = 0) : Effect { override fun describe() = "return the creature that died to the battlefield" }

/** Flicker: one of your creatures leaves and comes back, so its enters abilities happen again. */
data object Blink : Effect { override fun describe() = "blink your best creature with an enters ability" }

/** Vanishing/fading: a counter comes off each upkeep; sacrificed when the last one goes. */
data object Vanish : Effect { override fun describe() = "remove a time counter; sacrifice it when there are none" }

data class Activated(
    val cost: ManaCost, val tap: Boolean, val sacrificeSelf: Boolean, val effects: List<Effect>,
    /** Cost includes sacrificing another creature (paid with a small token, of [sacrificeTribe] if set). */
    val sacrificeCreature: Boolean = false,
    val sacrificeTribe: String? = null,
    /** "Tap ten untapped Elves you control": how many, and which type. */
    val tapCreatures: Int = 0,
    val tapTribe: String? = null,
    /** "Remove a +1/+1 counter from ~". */
    val removeCounter: Boolean = false,
    /** "Remove three charge counters from ~": other kinds of counters it needs. */
    val removeMarks: Int = 0,
    /** Used from the graveyard ("exile ~ from your graveyard", "return ~ from your graveyard"). */
    val fromGraveyard: Boolean = false,
    val lifeCost: Int = 0,
    val discardCost: Boolean = false,
    /** "{B}: Regenerate ~": used when it would be destroyed. */
    val regenerate: Boolean = false,
    val sorcery: Boolean = false,
) {
    fun describe() = (if (fromGraveyard) "from graveyard · " else "") + "activate (${cost.total} mana${if (tap) ", tap" else ""}${if (sacrificeSelf) ", sacrifice it" else ""}" +
        "${if (sacrificeCreature) ", sacrifice a ${sacrificeTribe ?: "creature"}" else ""}${if (tapCreatures > 0) ", tap $tapCreatures ${tapTribe ?: "creature"}s" else ""}" +
        "${if (removeCounter) ", remove a counter" else ""}${if (lifeCost > 0) ", $lifeCost life" else ""}${if (discardCost) ", discard a card" else ""}): " +
        (if (regenerate) "regenerate" else effects.joinToString("; ") { it.describe() })
}

/** A planeswalker ability: loyalty change and effects. */
data class Loyalty(val delta: Int, val effects: List<Effect>) {
    fun describe() = "loyalty ${if (delta >= 0) "+" else ""}$delta: " + effects.joinToString("; ") { it.describe() }
}

/** How much of a card the quick model understands. */
data class Coverage(val understood: List<String>, val missed: List<String>) {
    val status: Status get() = when {
        missed.isEmpty() -> Status.FULL
        understood.isEmpty() -> Status.NONE
        else -> Status.PARTIAL
    }
    enum class Status(val label: String) { FULL("Understood"), PARTIAL("Partly understood"), NONE("Not understood") }
}

/** Reads rules text into abilities. Pure functions over lowercase text with the card's name replaced by "~". */
object EffectParser {
    private val num = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "x" to XN, "that many" to 2, "dmgmany" to DMG, "gainmany" to LAST_GAIN)
    private fun n(word: String?): Int = word?.trim()?.let { num[it] ?: it.toIntOrNull() } ?: 1

    data class Parsed(
        val abilities: List<Ability>,
        val activated: List<Activated>,
        val loyalty: List<Loyalty>,
        /** Saga chapter effects, chapter I first. */
        val chapters: List<List<Effect>>,
        val coverage: Coverage,
    )

    /** Normalises rules text: lowercase, the card's name (and short name) as "~", no reminder text. */
    fun normalise(name: String, oracle: String): String {
        var t = oracle.lowercase().replace(rx("""\([^)]*\)"""), "").replace('−', '-')
        val full = name.substringBefore(" // ").lowercase()
        val short = full.substringBefore(",").trim()
        t = t.replace(full, "~")
        if (short.length >= 4) t = t.replace(rx("\\b${Regex.escape(short)}\\b"), "~")
        // "Hakbal of the Surging Soul" is just "Hakbal" in its own text.
        val first = full.substringBefore(" of ").substringBefore(" the ").trim()
        if (first != full && first.length >= 4) t = t.replace(rx("\\b${Regex.escape(first)}\\b"), "~")
        return t.replace(rx("""\bthis (creature|enchantment|artifact|equipment|vehicle|aura|saga|permanent|token|spacecraft|planet|class|case|room|attraction)\b"""), "~")
            .replace("enters the battlefield", "enters").replace("enter the battlefield", "enter")
    }

    private val colorWords = mapOf("white" to ManaColor.W, "blue" to ManaColor.U, "black" to ManaColor.B, "red" to ManaColor.R, "green" to ManaColor.G)
    private val basicTypes = mapOf("plains" to ManaColor.W, "island" to ManaColor.U, "swamp" to ManaColor.B, "mountain" to ManaColor.R, "forest" to ManaColor.G)

    /** "For each …" / "equal to …" in [text], if any. */
    fun scale(text: String): Scale? {
        rx("""devotion to ([a-z]+)( and ([a-z]+))?""").find(text)?.let { m ->
            return Scale(ScaleKind.DEVOTION, (colorWords[m.groupValues[1]] ?: 0) or (colorWords[m.groupValues[3]] ?: 0))
        }
        if (rx("""(equal to|x is) the number of cards in your hand|for each card in your hand""").containsMatchIn(text)) return Scale(ScaleKind.HAND)
        if (rx("""for each opponent|number of opponents""").containsMatchIn(text)) return Scale(ScaleKind.OPPONENTS)
        if (rx("""greatest power among|greatest toughness among""").containsMatchIn(text)) return Scale(ScaleKind.GREATEST_POWER)
        if (rx("""for each time it was kicked|times it was kicked""").containsMatchIn(text)) return Scale(ScaleKind.KICKED)
        if (rx("""(for each|number of) creatures? (cards? )?in your graveyard""").containsMatchIn(text)) return Scale(ScaleKind.GRAVEYARD_CREATURES)
        if (rx("""(for each|number of) cards? in your graveyard""").containsMatchIn(text)) return Scale(ScaleKind.GRAVEYARD)
        if (rx("""(for each|number of) creatures? (your opponents control|an opponent controls|you don't control)""").containsMatchIn(text)) return Scale(ScaleKind.OPP_CREATURES)
        if (rx("""(for each|number of) (other )?creatures? on the battlefield""").containsMatchIn(text)) return Scale(ScaleKind.ALL_CREATURES)
        if (rx("""(for each|number of) \+1/\+1 counters? on (~|it|that creature)""").containsMatchIn(text)) return Scale(ScaleKind.COUNTERS)
        rx("""tap (?:x|any number of) untapped ([a-z]+?)s? you control""").find(text)?.let { m -> return if (m.groupValues[1] == "creature") Scale(ScaleKind.CREATURES) else Scale(ScaleKind.TRIBE, tribe = singular(m.groupValues[1])) }
        if (rx("""number of attacking creatures""").containsMatchIn(text)) return Scale(ScaleKind.CREATURES)
        if (rx("""number of lands (that player|defending player|you) controls?""").containsMatchIn(text)) return Scale(ScaleKind.LANDS)
        rx("""(?:for each|number of) ([a-z]+?)s? on the battlefield""").find(text)?.let { m ->
            if (m.groupValues[1] !in setOf("creature", "permanent", "artifact", "land", "card", "other")) return Scale(ScaleKind.TRIBE, tribe = singular(m.groupValues[1]))
        }
        val m = Regex("""(?:for each|equal to the number of|x is the number of) (?:other )?([a-z]+?)s? (?:creatures? )?you control""").find(text) ?: return when {
            rx("""(x is|equal to) (~'s|its|that creature's|her|his|their) power""").containsMatchIn(text) -> Scale(ScaleKind.SOURCE_POWER)
            rx("""(x is|equal to) (that spell's|its|the sacrificed [a-z]+'s|that card's|the exiled card's) mana value""").containsMatchIn(text) -> Scale(ScaleKind.SPELL_MV)
            rx("""(x is|equal to) the (greatest|highest) mana value among""").containsMatchIn(text) -> Scale(ScaleKind.GREATEST_MV)
            else -> null
        }
        return when (val w = m.groupValues[1]) {
            "creature" -> Scale(ScaleKind.CREATURES)
            "artifact" -> Scale(ScaleKind.ARTIFACTS)
            "enchantment" -> Scale(ScaleKind.ENCHANTMENTS)
            "land" -> Scale(ScaleKind.LANDS)
            "permanent", "nonland permanent" -> Scale(ScaleKind.PERMANENTS)
            "token" -> Scale(ScaleKind.TOKENS)
            in basicTypes -> Scale(ScaleKind.LANDS_OF_COLOR, basicTypes.getValue(w))
            "plain" -> Scale(ScaleKind.LANDS_OF_COLOR, ManaColor.W)
            else -> Scale(ScaleKind.TRIBE, tribe = singular(w))
        }
    }

    fun singular(w: String): String = when {
        w.endsWith("ves") -> w.dropLast(3) + "f"
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun effects(raw: String): List<Effect> {
        // "Deals combat damage to a player, create that many…": that many = the damage dealt.
        val text = (if ("combat damage" in raw) raw.replace("that many", "dmgmany") else raw).replace(rx("""\bthat much\b"""), "dmgmany")
        val out = mutableListOf<Effect>()
        val sc = scale(text)
        fun scaled(base: Int) = if (sc != null && (base == 1 || base == XN)) sc else null
        // Tokens: one "create …" clause at a time.
        rx("""(?<!opponent )(?<!that player )(?<!its controller )(?<!owner )(?<!attacking player )creates? ([^.;]*?tokens?)([^.;]*)""").findAll(text).forEach { m ->
            val clause = m.groupValues[1]
            val rest = m.groupValues[2]
            if ("copy" in clause || "copies" in clause) return@forEach
            val count = n(rx("""^(a|an|one|two|three|four|five|six|x|that many|dmgmany|\d+)\b""").find(clause.removePrefix("s "))?.value)
            val kind = when {
                "treasure" in clause -> TokenKind.TREASURE
                "food" in clause -> TokenKind.FOOD
                "clue" in clause -> TokenKind.CLUE
                "creature" in clause || rx("""\d+/\d+""").containsMatchIn(clause) -> TokenKind.CREATURE
                else -> null
            } ?: return@forEach
            if (kind != TokenKind.CREATURE) { out += MakeTokens(count, kind, scale = scaled(count)); return@forEach }
            val pt = Regex("""(\d+|x)/(\d+|x)""").find(clause)
            val p = pt?.groupValues?.get(1)?.let { if (it == "x") 2 else it.toInt() } ?: 1
            val tough = pt?.groupValues?.get(2)?.let { if (it == "x") 2 else it.toInt() } ?: 1
            val type = Regex("""(?:\d+|x)/(?:\d+|x) ([a-z ]+?) (?:artifact )?(?:enchantment )?creature""").find(clause)?.groupValues?.get(1)
                ?.split(' ')?.filterNot { it in setOf("white", "blue", "black", "red", "green", "colorless", "and", "legendary", "snow") }
                ?.joinToString(" ")?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "Creature"
            val with = Regex("""with ([a-z ,]+)""").find(clause + rest)?.groupValues?.get(1).orEmpty()
            out += MakeTokens(count, kind, type, p, tough, Kw.of(with), scaled(count))
        }
        val wheel = Regex("""each player (discards their hand|shuffles their hand)[^.]*?,? (and |then )?draws (seven|that many|\d+|cards equal to[^.]*?|[a-z]+) cards?""").find(text)
        if (wheel != null) out += Wheel(if (wheel.groupValues[3] == "seven") 7 else wheel.groupValues[3].toIntOrNull() ?: 0)
        (rx("""\byou (?:may )?draw (a|an|one|two|three|four|five|six|seven|x|that many|dmgmany|\d+) (additional )?cards?""").find(text)
            ?: rx("""draws? (a|an|one|two|three|four|five|six|seven|x|that many|dmgmany|\d+) (additional )?cards?""").find(text))?.takeIf { wheel == null }?.let {
            val count = n(it.groupValues[1])
            when {
                rx("""each player draws""").containsMatchIn(text) -> out += Draw(count, scaled(count), eachPlayer = true)
                it.value.startsWith("you") || !rx("""(opponent|that player|its controller|its owner) draws""").containsMatchIn(text) -> out += Draw(count, scaled(count))
            }
        } ?: rx("""draws? cards equal to""").find(text)?.takeIf { wheel == null && sc != null }?.let { out += Draw(1, sc) }
        // Digging: "look at the top five cards … put one of them into your hand".
        if (out.none { it is Draw } && wheel == null) {
            val dig = Regex("""(look at|reveal) the top (\w+) cards of your library""").find(text)
            val put = rx("""put (a|an|one|two|three|up to (one|two|three)|any number|all|it|that card|those cards|them|one of them) [^.]*?(into your hand|in your hand)""").find(text)
            if (dig != null && put != null) {
                val shown = n(dig.groupValues[2]).let { if (it == XN) 3 else it }
                val k = when {
                    put.groupValues[2].isNotEmpty() -> n(put.groupValues[2])
                    put.groupValues[1] == "any number" || put.groupValues[1] == "all" || put.groupValues[1] == "those cards" || put.groupValues[1] == "them" -> (shown / 3).coerceAtLeast(1)
                    put.groupValues[1] == "it" || put.groupValues[1] == "that card" || put.groupValues[1] == "one of them" -> 1
                    else -> n(put.groupValues[1])
                }
                out += Draw(k.coerceAtMost(shown))
            } else if (rx("""reveal cards from the top of your library until you reveal [^.]*\. put (that card|it|those cards) into your hand""").containsMatchIn(text)) out += Draw(1)
            else if (rx("""(look at|reveal) the top (two|three|four) cards of your library,? (then )?put them back in any order""").containsMatchIn(text)) out += Scry(3, surveil = false)
        }
        val gainBack = Regex("""you gain (life equal to the life lost|that much life)""").containsMatchIn(text)
        rx("""each (opponent|other player) loses (\d+|x|one|two|three|that much|life equal to) (life)?""").find(text)?.let {
            val k = if (it.groupValues[2] == "life equal to") 1 else n(it.groupValues[2])
            out += LoseLife(k, each = true, scale = if (it.groupValues[2] == "life equal to") sc else scaled(k), gainBack = gainBack)
        } ?: rx("""(target|that|defending) (player|opponent) loses? (\d+|x|one|two|three|dmgmany|gainmany|life equal to) ?(life)?""").find(text)?.let {
            val k = if (it.groupValues[3] == "life equal to") 1 else n(it.groupValues[3])
            out += LoseLife(k, each = false, scale = if (it.groupValues[3] == "life equal to") sc else scaled(k), gainBack = gainBack)
        } ?: rx("""each player loses (\d+|x|one|two|three) life""").find(text)?.let { out += LoseLife(n(it.groupValues[1]), each = true, scale = scaled(n(it.groupValues[1]))) }
        rx("""deals? (\d+|x|dmgmany) damage to (each opponent|each player|target player|target opponent|target player or planeswalker|that player|defending player|each of your opponents|each other opponent|the chosen player|the player or planeswalker it's attacking|defending player or planeswalker)""").find(text)?.let {
            out += Damage(n(it.groupValues[1]), each = it.groupValues[2].startsWith("each"), scale = scaled(n(it.groupValues[1])))
        }
        rx("""deals? damage equal to (its|~'s|that creature's|the sacrificed creature's) power to (each opponent|any target|target creature|target player|target opponent|that player|defending player|target creature or planeswalker|each other creature)""").find(text)?.let {
            val to = it.groupValues[2]
            out += when {
                to == "each opponent" -> Damage(POWER, each = true)
                to.startsWith("target creature") -> Remove(RemovalKind.DAMAGE, "creature", POWER)
                to == "any target" -> Remove(RemovalKind.DAMAGE, "any", POWER)
                to == "each other creature" -> Sweep(POWER)
                else -> Damage(POWER, each = false)
            }
        }
        rx("""(?:you|target player) (?:may )?gains? (\d+|x|one|two|three|four|five|dmgmany|life equal to)( life)?""").find(text)?.let {
            when {
                it.groupValues[1] != "life equal to" -> out += GainLife(n(it.groupValues[1]), scaled(n(it.groupValues[1])))
                rx("""life equal to (its|that creature's|~'s|the sacrificed creature's|the exiled creature's) (power|toughness)""").containsMatchIn(text) -> out += GainLife(POWER)
                sc != null && !gainBack -> out += GainLife(1, sc)
            }
        }
        rx("""put (a|an|one|two|three|four|five|six|seven|eight|ten|x|that many|dmgmany|\d+) \+1/\+1 counters? on (each of up to (two|three|four) (?:other )?target (?:[a-z]+ )?(?:creatures|artifacts|permanents)|each of those creatures|each (other )?(creature|[a-z]+ creature|[a-z]+) you control|each creature|(up to one )?(other |another )?target [a-z ]*?creature( you control)?|target [a-z]+ you control|~|it|that creature|him|her|equipped creature|enchanted creature)""")
            .find(text)?.let {
                val where = it.groupValues[2]
                val tribe = it.groupValues[5].removeSuffix(" creature").takeIf { t -> t.isNotEmpty() && t != "creature" && t != "other" }?.let { t -> singular(t) }
                out += Counters(n(it.groupValues[1]), all = where.startsWith("each") && !where.startsWith("each of up"), self = where == "~" || where == "it" || where == "that creature" || where == "him" || where == "her",
                    scale = scaled(n(it.groupValues[1])), tribe = tribe, spread = if (where.startsWith("each of up to")) n(it.groupValues[3]) else 1)
            }
        rx("""~ enters with (a|an additional|one|two|three|four|five|six|x|\d+|a number of) (additional )?\+1/\+1 counters?""").find(text)?.let {
            val k = when (it.groupValues[1]) { "a number of" -> 1; "an additional" -> 1; else -> n(it.groupValues[1]) }
            out += Counters(k, all = false, self = true, scale = if (it.groupValues[1] == "a number of") sc else scaled(k))
        }
        rx("""\b(bolster|support|adapt|amass(?: [a-z]+)?) (\d+|x)""").find(text)?.let {
            val k = n(it.groupValues[2])
            out += when {
                it.groupValues[1] == "bolster" -> Counters(k, all = false, self = false)
                it.groupValues[1] == "support" -> Counters(1, all = false, self = false, spread = k)
                it.groupValues[1] == "adapt" -> Counters(k, all = false, self = true)
                else -> MakeTokens(1, TokenKind.CREATURE, "Army", k.coerceAtLeast(1), k.coerceAtLeast(1))
            }
        }
        if (rx("""that player mills that many cards""").containsMatchIn(text)) out += Mill(DMG, MillTarget.DAMAGED)
        else rx("""(each opponent|each player|target player|target opponent|that player|you) mills? (a|one|two|three|four|five|ten|x|\d+) cards?""").find(text)?.let {
            out += Mill(n(it.groupValues[2]), when (it.groupValues[1]) { "you" -> MillTarget.SELF; "each opponent", "each player" -> MillTarget.EACH_OPPONENT; "that player" -> MillTarget.DAMAGED; else -> MillTarget.OPPONENT })
        } ?: rx("""(^|, |\. |then |and )(you may )?mill (a|one|two|three|four|five|six|seven|eight|ten|x|\d+) cards?""").find(text)?.let { out += Mill(n(it.groupValues[3]), MillTarget.SELF) }
        rx("""(each opponent|target player|target opponent) gets? (a|one|two|three|x|\d+) poison counters?""").find(text)?.let {
            out += Poison(n(it.groupValues[2]), each = it.groupValues[1] == "each opponent")
        }
        if (rx("""\bproliferate\b""").containsMatchIn(text)) out += Proliferate
        if (rx("""when ~ dies, return it to its owner's hand|(^|\. )(you may )?return (it|~|that card) to (its owner's|your) hand""").containsMatchIn(text)) out += ReturnToHand
        rx("""exile target creature card from a graveyard\. create a token that's a copy of it(?:, except it's a (\d+)/(\d+) [a-z]+ ([a-z]+))?""").find(text)?.let { m ->
            out += GraveyardCopy(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].ifBlank { null })
        }
        if (rx("""opponent's graveyard[^.]*\. put it onto the battlefield under your control|from an opponent's graveyard onto the battlefield under your control""").containsMatchIn(text)) out += StealFromGraveyard
        rx("""\bearthbend (\d+|x)""").find(text)?.let { out += Earthbend(n(it.groupValues[1])) }
        if (rx("""distribute up to that many \+1/\+1 counters""").containsMatchIn(text) && "gained life" in text) out += Counters(LIFE_GAINED, all = false, self = false)
        pumps(text)?.let { out += it }
        rx("""(each|another target|target) ([a-z]+) (creature )?(you control )?explores|(~|it|that creature) explores|each creature you control explores""").find(text)?.let { m ->
            out += when {
                m.value.startsWith("each creature you control") -> Explore(ExploreWho.ALL)
                m.groupValues[5] == "~" -> Explore(ExploreWho.SELF)
                m.groupValues[5].isNotEmpty() -> Explore(ExploreWho.TRIGGERING)
                m.groupValues[2] == "creature" -> Explore(ExploreWho.ALL)
                else -> Explore(ExploreWho.TRIBE, singular(m.groupValues[2]))
            }
        }
        if (rx("""put a land card from your hand onto the battlefield""").containsMatchIn(text)) {
            val elseDraw = Regex("""if you don't, draw a card""").containsMatchIn(text)
            if (elseDraw) out.removeAll { it is Draw }
            out += LandFromHand(elseDraw)
        }
        if (rx("""(cast|play) [^.]*from the top of your library""").containsMatchIn(text)) out += TopOfLibrary
        rx("""create a token that's a copy of (target|another target|up to one target)? ?(nonlegendary )?creature( you control)?""").find(text)?.let {
            out += CopyCreature(token = true, ownOnly = it.groupValues[3].isNotEmpty())
        }
        if (rx("""enter as a copy of (any|a) (nonlegendary )?creature""").containsMatchIn(text)) out += CopyCreature(token = false, ownOnly = false)
        if ("extra turn" in text && "take" in text) out += ExtraTurn
        if ("additional combat phase" in text) out += ExtraCombat
        rx("""if you have (\d+) or more life, you win the game""").find(text)?.let { out += WinGame(WinCondition.LIFE, it.groupValues[1].toInt()) }
        if ("draw a card while your library has no cards in it, you win the game" in text) out += WinGame(WinCondition.EMPTY_LIBRARY)
        if (rx("""x is greater than or equal to the number of cards in your library, you win the game""").containsMatchIn(text)) out += WinGame(WinCondition.ORACLE)
        moreEffects(text, sc, wheel != null, out)
        return out
    }

    private const val NOUN = """(?:non[a-z]+ )*(?:creature|artifact|enchantment|planeswalker|permanent|land|battle)s?"""
    private val nounList = """$NOUN(?:(?:, |,? or |,? and/or )$NOUN)*"""
    private val removeRx = Regex("""(destroy|exile) (up to (one|two|three) |(two|three|four|five|six|x) )?(another |other )?target ($nounList)( an opponent controls| you don't control| that player controls| defending player controls| with [^.,]+| of an opponent's choice)?""")
    private val bounceRx = Regex("""return (up to (one|two|three) |(?:two|three|four|five|six|x) )?(another )?target ($nounList)( an opponent controls| you don't control)?(?! you control)[^.]*? to (its|their) owners?'?s? hands?""")
    private val shuffleAwayRx = Regex("""(owner of target ($nounList) shuffles it into|put target (attacking |blocking )?($nounList) (on the (top|bottom) of|into) its owner's library|target ($nounList)'s owner (shuffles it|puts it))""")
    private val edictRx = Regex("""(each opponent|target opponent|target player|each player|defending player|that player|each other player|each of your opponents) sacrifices? (a|an|one|two|three|x|\d+|that many)? ?(?:other )?(?:nontoken )?(?:non-?[a-z]+ )?(creature or planeswalker|artifact or enchantment|nonland permanent|creature with [a-z0-9 ]+|attacking creature|creatures?|artifacts?|enchantments?|permanents?|lands?)""")
    private val discardRx = Regex("""(each opponent|target opponent|target player|each player|that player|each other player|defending player) discards? (a|an|one|two|three|x|\d+|that many|their hand|a card at random)""")

    private fun who(s: String): Who = when (s) {
        "each opponent", "each other player", "each of your opponents" -> Who.EACH_OPPONENT
        "each player" -> Who.EACH_PLAYER
        "defending player" -> Who.DEFENDER
        else -> Who.OPPONENT
    }

    private fun countWord(s: String?): Int = when (s?.trim()) { null, "" -> 1; else -> n(s).let { if (it == XN) 2 else it } }

    /** Removal, edicts, discard, card selection, sweepers and other effects that aren't damage, life or tokens. */
    private fun moreEffects(text: String, sc: Scale?, isWheel: Boolean, out: MutableList<Effect>) {
        // Targeted removal. "Exile target creature you control" (a blink) and graveyard cards aren't removal.
        removeRx.findAll(text).firstOrNull { m ->
            val after = text.substring(m.range.last + 1).trimStart()
            !after.startsWith("you control") && !after.startsWith("card") && !m.groupValues[6].startsWith("land")
        }?.let { m ->
            val kind = if (m.groupValues[1] == "exile") RemovalKind.EXILE else RemovalKind.DESTROY
            out += Remove(kind, m.groupValues[6].replace(rx("s\\b"), ""), count = countWord(m.groupValues[3].ifBlank { m.groupValues[4] }).coerceAtLeast(1))
        }
        bounceRx.find(text)?.takeIf { out.none { it is Remove } }?.let { m ->
            out += Remove(RemovalKind.BOUNCE, m.groupValues[4].replace(rx("s\\b"), ""), count = countWord(m.groupValues[2]))
        }
        shuffleAwayRx.find(text)?.takeIf { out.none { it is Remove } }?.let { out += Remove(RemovalKind.EXILE, "permanent") }
        rx("""target creature( an opponent controls| you don't control)? gets -(\d+|x)/-(\d+|x)""").find(text)?.takeIf { out.none { it is Remove } }?.let {
            out += Remove(RemovalKind.MINUS, "creature", n(it.groupValues[3]))
        }
        rx("""deals? (\d+|x) damage to (up to one |another )?(target creature( or planeswalker)?|any target|target creature or player)( an opponent controls| you don't control)?""").find(text)
            ?.takeIf { out.none { it is Remove } && "damage divided" !in text }?.let {
                out += Remove(RemovalKind.DAMAGE, if (it.groupValues[3] == "any target") "any" else "creature", n(it.groupValues[1]))
            }
        if (rx("""deals (\d+|x) damage divided as you choose""").containsMatchIn(text) && out.none { it is Remove }) {
            out += Remove(RemovalKind.DAMAGE, "any", n(rx("""deals (\d+|x) damage divided""").find(text)!!.groupValues[1]))
        }
        rx("""fights? (target|up to one target|another target) creature""").find(text)?.takeIf { out.none { it is Remove } }?.let { out += Remove(RemovalKind.FIGHT, "creature") }
        // Sweepers that damage or shrink every creature.
        rx("""damage to each (other |non[a-z]+ )?creature( your opponents control| you don't control)?""").find(text)?.let { m ->
            val amt = Regex("""deals? (\d+|x) damage to each""").find(text)?.groupValues?.get(1)?.let { n(it) } ?: return@let
            val sentence = text.substring(m.range.first).substringBefore(".")
            out += Sweep(amt, players = rx("""each (player|opponent)""").containsMatchIn(sentence), flyersSafe = "without flying" in sentence,
                oppOnly = m.groupValues[2].isNotEmpty(), scale = if (amt == XN) sc else null)
        } ?: rx("""(all|each) (other |non-?[a-z]+ )?creatures?( your opponents control| you don't control)? gets? -(\d+|x)/-(\d+|x)""").find(text)?.let { m ->
            out += Sweep(n(m.groupValues[5]), oppOnly = m.groupValues[3].isNotEmpty(), scale = if (m.groupValues[5] == "x") sc else null)
        } ?: rx("""creatures (your opponents control|you don't control) get -(\d+|x)/-(\d+|x)""").find(text)?.let { m ->
            out += Sweep(n(m.groupValues[3]), oppOnly = true, scale = if (m.groupValues[3] == "x") sc else null)
        }
        edictRx.find(text)?.let { m ->
            val what = m.groupValues[3].let { w -> if (w.startsWith("creature with") || w == "attacking creature") "creature" else w.removeSuffix("s") }
            out += Edict(countWord(m.groupValues[2]), who(m.groupValues[1]), what)
        }
        if (!isWheel) {
            discardRx.find(text)?.let { m ->
                val k = when (m.groupValues[2]) { "their hand" -> 99; "a card at random" -> 1; else -> countWord(m.groupValues[2]) }
                out += Discard(k, who(m.groupValues[1]))
            }
            rx("""(^|then |, |and |\. )(you )?discard (a|one|two|three|x|\d+|up to (one|two|three)) cards?""").find(text)?.let {
                out += Discard(countWord(it.groupValues[4].ifBlank { it.groupValues[3] }), Who.SELF)
            } ?: if (rx("""(^|then |, |and |\. )(you )?discard your hand""").containsMatchIn(text)) out += Discard(99, Who.SELF) else Unit
        }
        rx("""\b(scry|surveil) (\d+|x)""").find(text)?.let { out += Scry(n(it.groupValues[2]).let { k -> if (k == XN) 2 else k }, it.groupValues[1] == "surveil") }
        rx("""\btap (up to (one|two|three|four) )?(target|all|each) (creatures?|permanents?|artifacts? or creatures?|nonland permanents?)( an opponent controls| your opponents control| you don't control| target player controls| defending player controls| target opponent controls)?""").find(text)?.let { m ->
            if (m.groupValues[5].isNotEmpty() || m.groupValues[3] == "target") out += TapCreatures(if (m.groupValues[3] == "target") countWord(m.groupValues[2]) else 10)
        }
        rx("""\bgoad (up to (one|two) )?(target|each|all) """).find(text)?.let { out += TapCreatures(if (it.groupValues[3] == "target") countWord(it.groupValues[2]) else 10, goad = true) }
        if (rx("""you become the monarch|take the initiative""").containsMatchIn(text)) out += Monarch
        if (rx("""venture into the dungeon""").containsMatchIn(text)) out += Venture
        // Free spells.
        rx("""cast (a|an|up to one|one|any number of)? ?[a-z ]*?(spells?|cards?) with mana value (\d+|x) or less from (your hand|among [^.]*?) without paying""").find(text)?.let {
            out += FreeCast(n(it.groupValues[3]).let { v -> if (v == XN) 4 else v }, fromLibrary = !it.groupValues[4].startsWith("your hand"))
        } ?: rx("""\bdiscover (\d+|x)""").find(text)?.let { out += FreeCast(n(it.groupValues[1]).let { v -> if (v == XN) 4 else v }, fromLibrary = true) }
            ?: if (rx("""(exile|reveal) the top[^.]*\.[^.]*(you may )?(cast|play) (it|that card|them|those cards|any number of [^.]*|a spell from among them)[^.]*without paying""").containsMatchIn(text) ||
                rx("""until you (exile|reveal) a nonland card[^.]*\.[^.]*cast (it|that card)[^.]*without paying""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = true)
            else if (rx("""you may put an? (creature|permanent|artifact|nonland permanent|[a-z]+ creature) card from your hand onto the battlefield""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = false)
            else Unit
        // Mana from spells and triggers (mana abilities are read separately).
        rx("""\badd ((\{[wubrgc]\})+)""").find(text)?.let { out += AddMana(rx("""\{""").findAll(it.groupValues[1]).count()) }
            ?: rx("""\badd (one|two|three|four|x) mana""").find(text)?.let { out += AddMana(n(it.groupValues[1]).let { v -> if (v == XN) 2 else v }) }
        rx("""gain control of (target|up to one target|another target|all) ([a-z ]+?)( an opponent controls| you don't control| target player controls| target opponent controls)?( until end of turn|\.|$| for as long|,)""").find(text)?.let { m ->
            val what = m.groupValues[2].let { w -> if ("artifact" in w && "creature" !in w) "artifact" else "creature" }
            out += Steal(untilEndOfTurn = m.groupValues[4] == " until end of turn" || "until end of turn" in text.substring(m.range.first).substringBefore("."), what)
        }
        rx("""(return|put) (target|up to one target|a|another target|up to (two|three) target|all|x target)? ?([a-z ]*?)cards? from (your|a|an opponent's|any) graveyards? (to|onto) (the battlefield|your hand)""").find(text)?.let { m ->
            if (out.none { it is ReturnToHand || it is StealFromGraveyard }) out += Reanimate(m.groupValues[7] == "the battlefield", anyPermanent = "creature" !in m.groupValues[4])
        }
        rx("""search your library for (up to (one|two|three|x) |a |an )?(basic lands?|lands?|basic (?:forest|plains|island|swamp|mountain)|(?:forest|plains|island|swamp|mountain)s?(?:,? or (?:forest|plains|island|swamp|mountain))*)( cards?)?""").find(text)?.let { m ->
            val k = countWord(m.groupValues[2].ifBlank { null })
            val after = text.substring(m.range.last + 1)
            val bf = Regex("""put (it|them|that card|those cards|one|one of them|both) onto the battlefield""").find(after)
            when {
                bf != null && bf.groupValues[1].startsWith("one") && "into your hand" in after -> out += FetchLands(1, k - 1)
                bf != null -> out += FetchLands(k, 0)
                "into your hand" in after.substringBefore("shuffle") -> out += FetchLands(0, k)
            }
        }
        if (rx("""(^|\. |, |then |and )sacrifice ~(\.|$| at)""").containsMatchIn(text.replace(rx("\"[^\"]*\""), "")) && "if you do" !in text && "unless" !in text) out += SacrificeSelf
        if (rx("""\bpopulate\b""").containsMatchIn(text)) out += Populate
        if (rx("""create a token that's a copy of ~""").containsMatchIn(text)) out += CopySelf
        if (rx("""prevent all combat damage that would be dealt (this turn|by creatures)""").containsMatchIn(text)) out += Fog
        rx("""\binvestigate( twice| three times)?""").find(text)?.let { out += MakeTokens(when (it.groupValues[1]) { " twice" -> 2; " three times" -> 3; else -> 1 }, TokenKind.CLUE) }
        if (rx("""\bconnives?\b""").containsMatchIn(text) && out.none { it is Draw }) { out += Draw(1); out += Discard(1, Who.SELF) }
        if (rx("""\b(manifest|cloak)( dread)?\b""").containsMatchIn(text)) out += MakeTokens(1, TokenKind.CREATURE, "Face-down", 2, 2)
        rx("""\bincubate (\d+|x)""").find(text)?.let { val k = countWord(it.groupValues[1]); out += MakeTokens(1, TokenKind.CREATURE, "Phyrexian", k, k) }
        if (rx("""you get ((\{e\})+|an amount of \{e\}|[a-z]+ \{e\}|that many \{e\})""").containsMatchIn(text)) out += Energy
        rx("""that player draws (an additional card|a card|(two|three) additional cards)""").find(text)?.let { out += ActiveDraws(if (it.groupValues[2].isNotEmpty()) n(it.groupValues[2]) else 1) }
        if (rx("""attach (~|it|that equipment) to (it|that creature|target creature you control|target creature)""").containsMatchIn(text)) out += Attach
        if (rx("""return the exiled (card|cards|permanent|permanents|creature) to (the battlefield|their owners'? hands?)""").containsMatchIn(text)) out += ExileEnds
        rx("""put (a|one|two|three|x|\d+) -1/-1 counters? on (up to one )?target creature""").find(text)?.takeIf { out.none { it is Remove } }?.let { out += Remove(RemovalKind.MINUS, "creature", n(it.groupValues[1])) }
        if (rx("""double the number of (\+1/\+1 counters|each kind of counter) on""").containsMatchIn(text)) out += DoubleCounters
        if (rx("""prevent all (combat )?damage that would be dealt to you this turn""").containsMatchIn(text) && out.none { it == Fog }) out += Fog
        if (rx("""\bcopy (target instant or sorcery spell|that spell|it|target instant spell|target sorcery spell)\b""").containsMatchIn(text) && "token" !in text) out += CopySpell
        rx("""(look at|reveal) the top card of your library\. if it's an? ([a-z ]+?) card(?: of the chosen type)?, (you may )?(reveal it and )?put (it|that card) (onto the battlefield|into your hand)""").find(text)?.let { m ->
            val match = m.groupValues[2].let { w -> if ("land" in w && "non" !in w) "land" else if ("creature" in w) "creature" else "nonland" }
            out += TopCard(match, m.groupValues[6] == "onto the battlefield", rx("""otherwise, put (it|that card) into your hand""").containsMatchIn(text))
        }
        // Impulse draw: "exile the top two cards of your library. Until end of turn, you may play them."
        if (out.none { it is Draw || it is FreeCast }) rx("""exile the top (card|(two|three|four|five|x|that many|dmgmany|\d+) cards) of your library[^.]*\.[^.]*(you may (cast|play)|until (the )?end of)""").find(text)?.let {
            out += Draw(if (it.groupValues[2].isEmpty()) 1 else n(it.groupValues[2]))
        }
        if (rx("""loses half (their|his or her) life""").containsMatchIn(text)) out += HalfLife
        if (rx("""you control enchanted (creature|permanent|artifact)""").containsMatchIn(text)) out += Steal(untilEndOfTurn = false, if ("artifact" in text) "artifact" else "creature")
        rx("""\buntap (~|all lands you control|up to (one|two|three|four|five|x) lands|all (nonland )?permanents you control|all creatures you control|(another )?target (creature|permanent|artifact|land)|each [a-z ]*permanent you control[a-z ]*|each [a-z ]*creature you control[a-z ]*)""").find(text)?.let {
            out += Untap(when { "land" in it.value -> "land"; it.groupValues[1] == "~" -> "self"; "target" in it.value -> "target"; "creature" in it.value -> "creature"; else -> "all" },
                if (it.groupValues[2].isNotEmpty()) n(it.groupValues[2]).let { k -> if (k == XN) 3 else k } else 10)
        }
        rx("""(put|you may put) (a|an|one|two|three|x|\d+) ([a-z]+) counters? on ~|~ enters with (a|an|one|two|three|x|\d+) ([a-z]+) counters? on it|you get an? (experience) counter""").find(text)?.let { m ->
            out += Mark(countWord(m.groupValues[2].ifBlank { m.groupValues[4] }.ifBlank { "a" }))
        }
        rx("""~ enters with (a|one|two|three|\d+) -1/-1 counters?""").find(text)?.let { out += Counters(-countWord(it.groupValues[1]), all = false, self = true) }
        rx("""put (a|one|two|x|\d+) -1/-1 counters? on each creature( your opponents control| you don't control)?""").find(text)?.let {
            out += Sweep(n(it.groupValues[1]).let { k -> if (k == XN) XN else k }, oppOnly = it.groupValues[2].isNotEmpty())
        }
        rx("""deals damage to (each opponent|target player|target opponent|each player) equal to""").find(text)?.let { if (sc != null && out.none { it is Damage }) out += Damage(1, each = it.groupValues[1].startsWith("each"), scale = sc) }
        if (rx("""deals damage to target creature equal to""").containsMatchIn(text) && out.none { it is Remove }) out += Remove(RemovalKind.DAMAGE, "creature", 4)
        rx("""each creature you control[^.]* gets \+(\d+)/\+(\d+)""").find(text)?.let { if (out.none { it is Pump }) out += Pump(it.groupValues[1].toInt(), it.groupValues[2].toInt(), PumpTarget.ALL, keywords = Kw.of(text.substringAfter("gains ", ""))) }
        rx("""(creatures[^.]*your opponents control|target creature|each creature defending player controls|creatures defending player controls) can't block this turn""").find(text)?.let {
            if (out.none { it is TapCreatures }) out += TapCreatures(if (it.groupValues[1] == "target creature") 1 else 10)
        }
        rx("""(?:\{[^}]+\})+: level (\d+)|^level (\d+)$""").find(text)?.let { out += ClassLevel((it.groupValues[1].ifBlank { it.groupValues[2] }).toInt()) }
        if (rx("""\bexile (target|up to (one|two|three) target|all) cards? from (a|an opponent's|target player's|all|any) graveyards?|exile (target player's|each opponent's|all) graveyards?|exile all cards from (all graveyards|target player's graveyard)|put target card from a graveyard on the bottom|(target player|each player) shuffles [^.]*graveyards? into (their|its owner's|his or her) library|target player exiles a card from their graveyard""").containsMatchIn(text)) out += GraveHate
        if (rx("""you may cast your commander from the command zone without paying""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = false, commander = true)
        if (rx("""(draw cards|cards) equal to (its|~'s|that creature's) power""").containsMatchIn(text) && out.none { it is Draw }) out += Draw(POWER)
        if (rx("""draw cards equal to the difference|draw cards until you have""").containsMatchIn(text) && out.none { it is Draw }) out += Draw(2)
        if (rx("""return all attacking creatures to (their|its) owners?'s? hands?""").containsMatchIn(text)) out += Fog
        rx("""create (a|an|one|two|three|x|\d+) (tapped )?(gold|powerstone|vibranium|lander|blood|junk|map|shard|cache|clue|food|treasure) tokens?""").find(text)?.takeIf { out.none { it is MakeTokens } }?.let { m ->
            val kind = when (m.groupValues[3]) { "gold", "powerstone", "vibranium", "lander", "treasure", "cache" -> TokenKind.TREASURE; "food" -> TokenKind.FOOD; else -> TokenKind.CLUE }
            out += MakeTokens(countWord(m.groupValues[1]), kind)
        }
        rx("""distribute (two|three|four|x|\d+) \+1/\+1 counters among""").find(text)?.takeIf { out.none { it is Counters } }?.let { out += Counters(1, all = false, self = false, spread = countWord(it.groupValues[1])) }
        if (rx("""create (a|x|two) tokens? that(?: is| are|'s) a cop(y|ies) of (that creature|it|equipped creature|enchanted creature|another target [a-z ]*creature|target [a-z ]*permanent you control|another target nonland permanent you control|target creature you control)""").containsMatchIn(text) && out.none { it is CopyCreature }) out += CopyCreature(token = true, ownOnly = true)
        if (rx("""return the chosen cards to the battlefield|return (them|those cards) to the battlefield""").containsMatchIn(text) && out.none { it is Reanimate }) out += Reanimate(true)
        if (rx("""each player (exiles all creature cards from their graveyard, then sacrifices all creatures|sacrifices all creatures they control, then)|return all (artifact and enchantment|creature) cards from all graveyards to the battlefield""").containsMatchIn(text)) out += MassReanimate("creature" !in text.substringAfter("return all").substringBefore("cards"))
        rx("""each other creature that's attacking[^.]* gets \+(\d+)/\+(\d+)""").find(text)?.let { if (out.none { it is Pump }) out += Pump(it.groupValues[1].toInt(), it.groupValues[2].toInt(), PumpTarget.ALL) }
        if (rx("""exile (that many|dmgmany|two|three|x) cards from the top of your library[^.]*\.[^.]*you may (cast|play)""").containsMatchIn(text) && out.none { it is Draw }) out += Draw(DMG)
        if (out.none { it is Draw || it is FreeCast } && "exile" in text && rx("""you may (cast|play|look at and cast|look at and play) (that card|it|them|those cards|the exiled card|that spell|spells from among|a spell from among|lands and cast spells from among)""").containsMatchIn(text)) out += Draw(1)
        if (out.none { it is FreeCast } && rx("""(until (you|they) (exile|reveal) an? [a-z ]+ card[^.]*|from among (those cards|them)[^.]*)\.?[^.]*(you may )?cast (it|that card|a spell|that spell)?[^.]*without paying""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = true)
        if (out.none { it is FreeCast } && rx("""(exile|reveal)[^.]*\btop\b[^.]*(\. ?[^.]*)?(you may )?(cast|play) [^.]*without paying""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = true)
        if (out.none { it is FreeCast } && rx("""(put|you may put) (a|an) [a-z ,]*?(permanent|creature|artifact) card[^.]* from among them onto the battlefield""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = true)
        if (out.none { it is FreeCast } && rx("""until you reveal an? [a-z ]+ card\. (you may )?put (that card|it) onto the battlefield""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = true)
        rx("""create (a|two) (tapped )?tokens? that(?: is| are|'s) a cop(?:y|ies) of (target|another target) (artifact|token|non-saga token|artifact or creature|permanent)( you control)?""").find(text)?.let {
            if (out.none { e -> e is CopyCreature || e == Populate }) out += if (it.groupValues[4].contains("token")) Populate else CopyPermanent(it.groupValues[4].removeSuffix(" or creature"))
        }
        if (out.none { it is FetchLands } && rx("""(look at|reveal) the top (\w+) cards[^.]*\.[^.]*put (an?|all) (basic )?land cards? from among them onto the battlefield""").containsMatchIn(text)) out += FetchLands(1, 0)
        if (out.none { it is FetchLands } && rx("""searches their library for (a|up to (one|two)) basic land cards?, puts? (it|them) onto the battlefield""").containsMatchIn(text)) out += FetchLands(1, 0)
        rx("""cast (target|an?) (instant or sorcery|instant|sorcery) card( with mana value (\d+) or less)? from (your|that player's|an opponent's|a) graveyard""").find(text)?.let { out += GraveCast(it.groupValues[4].toIntOrNull() ?: 99) }
        if (rx("""each opponent reveals cards from the top of their library until they reveal a land card, then puts those cards into their graveyard""").containsMatchIn(text)) out += Mill(3, MillTarget.EACH_OPPONENT)
        if (rx("""gain half x life and draw half x cards""").containsMatchIn(text)) { out += Draw(2); out += GainLife(2) }
        rx("""damage equal to the sacrificed [a-z]+'s (mana value|power) to (any target|target creature|each opponent)""").find(text)?.let { if (out.none { it is Remove || it is Damage }) out += Remove(RemovalKind.DAMAGE, "any", 2) }
        rx("""creatures that aren't of the chosen type get -(\d+)/-(\d+)""").find(text)?.let { out += Sweep(it.groupValues[2].toInt(), oppOnly = true) }
        rx("""up to (x|one|two|three) target creatures gain ([a-z ,]+) until end of turn""").find(text)?.let { if (out.none { it is Pump }) out += Pump(0, 0, PumpTarget.ALL, keywords = Kw.of(it.groupValues[2])) }
        if (rx("""the exiled card's owner creates|return the exiled (card|cards) to (the battlefield|their owners?'? hands?)""").containsMatchIn(text) && out.none { it == ExileEnds }) out += ExileEnds
        if (rx("""^exile the top card of your library\.?$""").containsMatchIn(text.trim()) && out.isEmpty()) out += TopOfLibrary
        if (rx("""creatures (target player|that player) controls? get \+(\d+)/\+(\d+) until end of turn""").containsMatchIn(text) && out.none { it is Pump }) {
            val m = rx("""\+(\d+)/\+(\d+)""").find(text)!!
            out += Pump(m.groupValues[1].toInt(), m.groupValues[2].toInt(), PumpTarget.ALL)
        }
        rx("""\bmonstrosity (\d+|x)""").find(text)?.let { out += Monstrosity(n(it.groupValues[1]).let { v -> if (v == XN) 3 else v }) }
        extraEffects(text, sc, out)
    }

    private val kwCounterRx = Regex("""put (?:a|an|your choice of an?) ([a-z0-9+/, ]+?) counter on (~|it|that creature|him|her|target creature you control|target creature|another target (?:attacking )?creature(?: you control)?|up to one (?:other )?target creature(?: you control)?|target [a-z-]+ creature you control|another target creature)( an opponent controls| you don't control)?""")
    private val oppTokensRx = Regex("""(target opponent|each opponent|enchanted opponent|that player|its controller|enchanted player|defending player|each other player) creates? ([^.;]*?tokens?)([^.;]*)""")

    /** A "create … token" clause as tokens, or null when it isn't one the model makes. */
    private fun tokenClause(clause: String, rest: String, scaled: (Int) -> Scale?): MakeTokens? {
        if ("copy" in clause || "copies" in clause) return null
        val count = n(rx("""^(a|an|one|two|three|four|five|six|x|that many|dmgmany|\d+)\b""").find(clause.removePrefix("s "))?.value)
        val kind = when {
            "treasure" in clause -> TokenKind.TREASURE
            "food" in clause -> TokenKind.FOOD
            "clue" in clause -> TokenKind.CLUE
            "creature" in clause || rx("""\d+/\d+""").containsMatchIn(clause) -> TokenKind.CREATURE
            else -> null
        } ?: return null
        if (kind != TokenKind.CREATURE) return MakeTokens(count, kind, scale = scaled(count))
        val pt = Regex("""(\d+|x)/(\d+|x)""").find(clause)
        val p = pt?.groupValues?.get(1)?.let { if (it == "x") 2 else it.toInt() } ?: 1
        val tough = pt?.groupValues?.get(2)?.let { if (it == "x") 2 else it.toInt() } ?: 1
        val type = Regex("""(?:\d+|x)/(?:\d+|x) ([a-z ]+?) (?:artifact )?(?:enchantment )?creature""").find(clause)?.groupValues?.get(1)
            ?.split(' ')?.filterNot { it in setOf("white", "blue", "black", "red", "green", "colorless", "and", "legendary", "snow") }
            ?.joinToString(" ")?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "Creature"
        val with = Regex("""with ([a-z ,]+)""").find(clause + rest)?.groupValues?.get(1).orEmpty()
        return MakeTokens(count, kind, type, p, tough, Kw.of(with), scaled(count))
    }

    /** Keyword counters, opponents' tokens, your own life loss and sacrifices, graveyard-to-hand and other late additions. */
    private fun extraEffects(text: String, sc: Scale?, out: MutableList<Effect>) {
        // Blink, then death-return.
        if (rx("""exile (another |up to one |up to two |two |any number of )?(other )?target (nontoken |non-?[a-z]+ )?(creatures?|permanents?|artifacts? or creatures?|creatures? and/or planeswalkers|nonland permanents?) you control[^.]*?[.,] ?(then |at the beginning of the next end step, )?(you may )?return (it|that card|them|those cards|each of them|the exiled card|that permanent) to the battlefield|^exile (it|~|enchanted creature)[^.]*[.,] (then )?return (it|that card) to the battlefield""").containsMatchIn(text)) {
            out += Blink
            if ("graveyard" !in text) out.removeAll { it is Reanimate }
        }
        else rx("""return (it|that card|that creature|them|those cards) to the battlefield""").find(text)?.takeIf { out.none { e -> e is Reanimate } }?.let {
            out += ReturnDied(if (rx("""with a flying counter""").containsMatchIn(text)) Kw.FLYING else 0)
        }
        // Damage "equal to" something the model can count, or about 2.
        if (out.none { it is Damage || it is Remove || it is Sweep }) rx("""deals? damage equal to ([^.]+?) to (any target|target creature or planeswalker|target creature|target player or planeswalker|target player|target opponent|each opponent|that player|defending player|the player or planeswalker it's attacking|each other creature|each non-[a-z]+ creature|that creature|any other target)""").find(text)?.let { m ->
            val eq = m.groupValues[1]
            val k = if ("power" in eq && "twice" !in eq) POWER else 1
            val s = if (k == 1) (scale("equal to $eq") ?: scale("for each " + eq.substringAfter("number of "))) else null
            val amt = if (k == 1 && s == null) 2 else k
            out += when (val to = m.groupValues[2]) {
                "each opponent" -> Damage(amt, each = true, scale = s)
                "target player", "target opponent", "that player", "defending player", "the player or planeswalker it's attacking", "target player or planeswalker" -> Damage(amt, each = false, scale = s)
                "each other creature" -> Sweep(amt, scale = s)
                else -> if (to.startsWith("each non")) Sweep(amt, oppOnly = true, scale = s) else Remove(RemovalKind.DAMAGE, if (to == "any target" || to == "any other target") "any" else "creature", if (s == null) amt else 3)
            }
        }
        if (out.none { it is FreeCast } && rx("""you may cast (a|an) ([a-z ]+ )?(spell|card) from your hand without paying|you may put an? [a-z, ]+ (creature|permanent) card from your hand onto the battlefield""").containsMatchIn(text)) out += FreeCast(99, fromLibrary = false)
        // Role tokens and mutagens: about a permanent +1/+1 on that creature.
        rx("""create an? [a-z ]+ role token attached to (~|it|another target creature you control|target creature you control|up to one target creature you control)|create an? mutagen token""").find(text)?.let {
            if (out.none { e -> e is Counters }) out += Counters(1, all = false, self = it.groupValues[1] == "~" || it.groupValues[1] == "it")
        }
        if (rx("""^time travel\.?$""").containsMatchIn(text.trim())) out += Minor("time travel")
        kwCounterRx.find(text)?.let { m ->
            if (m.groupValues[3].isNotEmpty()) return@let
            val bits = Kw.of(m.groupValues[1]) or (if ("shield" in m.groupValues[1]) Kw.INDESTRUCTIBLE else 0)
            // "Your choice of a +1/+1, first strike, or menace counter": one of them.
            val kw = if (m.groupValues[1].contains(" or ")) Integer.lowestOneBit(bits) else bits
            if (kw != 0) out += KeywordCounter(kw, self = m.groupValues[2] in setOf("~", "it", "him", "her"))
        }
        if (out.none { it is MakeTokens }) oppTokensRx.find(text)?.takeIf { "attacking player" !in it.value }?.let { m ->
            tokenClause(m.groupValues[2], m.groupValues[3]) { null }?.let { t -> out += OppTokens(t, each = m.groupValues[1] == "each opponent" || m.groupValues[1] == "each other player") }
        }
        rx("""(^|\. |, |then |and |if you do, )you lose (\d+|x|one|two|three|four|five) life|~ deals (\d+) damage to you\b""").find(text)?.let {
            out += LoseLifeSelf(n(it.groupValues[2].ifBlank { it.groupValues[3] }).let { k -> if (k == XN) 2 else k })
        }
        rx("""^(you )?sacrifice (a|an|two) (creature|artifact|land|permanent|nonland permanent)s?\.?$""").find(text.trim())?.let {
            out += Edict(countWord(it.groupValues[2]), Who.SELF, it.groupValues[3])
        }
        if (out.none { it is Reanimate || it == ReturnToHand }) {
            rx("""return to your hand [^.]*?cards? (in|from) your graveyard|return target card from a graveyard to its owner's hand|return to the battlefield target [a-z ]*card in your graveyard|(return|put) [^.]*?cards?[^.]*? from (your|a|any) graveyards? (to|onto) (the battlefield|your hand)""").find(text)?.let { m ->
                val toBf = "to the battlefield" in m.value || "onto the battlefield" in m.value
                out += Reanimate(toBf, anyPermanent = "creature" !in m.value)
            }
        }
        if (out.none { it is Draw } && rx("""draw cards equal to""").containsMatchIn(text)) {
            out += if (rx("""draw cards equal to [^.]*power""").containsMatchIn(text)) Draw(POWER) else Draw(2)
        }
        // Forced attacks: the opposing creatures end up tapped on your turn, much like goad.
        rx("""creatures (your opponents control|target player controls|target opponent controls|defending player controls|that player controls|enchanted player controls) attack (this turn|each combat) if able|(another target|target) creature (an opponent controls )?attacks (target opponent )?this turn if able|all creatures attack each combat if able""").find(text)?.let {
            if (out.none { e -> e is TapCreatures }) out += TapCreatures(if (it.value.startsWith("creatures") || it.value.startsWith("all")) 10 else 1, goad = true)
        }
        // Drawbacks and choices that make no difference here.
        rx("""^(?:you may )?move (?:any number of|a|one or more) (?:\+1/\+1 )?counters? from ~ onto|^its controller gains \d+ life\.?$|^(?:you may )?exile that card from your graveyard\.?$|^(?:you may )?return (?:a|another) (?:[a-z ]+ )?creature you control to its owner's hand|^(?:secretly )?choose an opponent\.?$|you may reselect which player|^choose (?:left or right|odd or even|a color|a creature type)\.?$""").find(text.trim())?.let {
            if (out.isEmpty()) out += Minor(it.value.take(40))
        }
        if (rx("""\b(you|target player|each player|that player) gets? (a|one|two|three|four|x|\d+) rad counters?|\bget (a|one|two|three|four|x|\d+) rad counters?""").containsMatchIn(text) && out.none { it is Mill }) {
            // Rad counters: mill that many at their next main phase and lose 1 life per nonland card milled.
            val m = rx("""(you|target player|each player|that player|defending player|they) (?:may )?gets? (a|one|two|three|four|x|\d+) rad""").find(text)
            if (m != null && m.groupValues[1] != "you") { val k = countWord(m.groupValues[2]); out += Mill(k, if (m.groupValues[1] == "each player") MillTarget.EACH_OPPONENT else MillTarget.OPPONENT); out += LoseLife(((k + 1) / 2).coerceAtLeast(1), each = m.groupValues[1] == "each player") }
        }
        rx("""put a number of \+1/\+1 counters (?:equal to ([^.]+?) )?on ([^.]+?)(?: equal to ([^.]+))?(\.|$)""").find(text)?.takeIf { out.none { it is Counters } }?.let { m ->
            val eq = m.groupValues[1] + m.groupValues[3]
            val where = m.groupValues[2]
            val self = where == "~" || where == "it"
            val k = if ("power" in eq) POWER else 1
            out += Counters(k, all = where.startsWith("each"), self = self, scale = if (k == 1) scale("for each " + eq.substringAfter("number of ").substringAfter("amount of ")) ?: scale(eq) else null)
        }
    }

    /** Creature type a trigger is limited to: "another Merfolk you control", "of the chosen type" ("chosen"), or null. */
    fun tribeOf(line: String): String? {
        if ("of the chosen type" in line) return "chosen"
        val m = Regex("""^whenever (~ or )?(another|a|an|one or more) (other )?(nontoken )?([a-z]+) (creatures? |tokens? )?(you control|dies)""").find(line) ?: return null
        val w = m.groupValues[5]
        return if (w in setOf("creature", "creatures", "nontoken", "other")) null else singular(w)
    }

    /** "+N/+N until end of turn" effects. */
    private fun pumps(text: String): Pump? {
        rx("""target ([a-z]+ )?creature( you control)? can't be blocked this turn""").find(text)?.let { m ->
            return Pump(0, 0, PumpTarget.ANOTHER, m.groupValues[1].trim().ifBlank { null }?.let { singular(it) }, Kw.UNBLOCKABLE)
        }
        if ("until end of turn" !in text) return null
        rx("""(up to one )?target creature( you control)? gains ([a-z ,]+?) until end of turn""").find(text)?.takeIf { "gets" !in text }?.let { m ->
            return Pump(0, 0, PumpTarget.ANOTHER, null, Kw.of(m.groupValues[3]))
        }
        val kw = Regex("""gains? ([a-z ,]+?)( and gets| until|\.|$)""").find(text)?.groupValues?.get(1)?.let { Kw.of(it) } ?: 0
        rx("""gets \+x/\+x until end of turn, where x is its power""").find(text)?.let { return Pump(0, 0, PumpTarget.TRIGGERING, keywords = kw, doublePower = true) }
        // "+X/+X where X is …" for the team or one creature.
        rx("""(other attacking creatures( you control)?|attacking creatures( you control)?|creatures you control|each other creature you control|another target (?:attacking )?creature( you control)?|target (?:attacking )?creature( you control)?|~|it) (gains? [a-z ,]+ and )?gets? \+x/\+(x|0)""").find(text)?.let { m ->
            // X we can't count is played as 2.
            val s = scale(text) ?: return Pump(2, if (m.value.endsWith("+x")) 2 else 0, when {
                "creatures" in m.groupValues[1] -> PumpTarget.ALL; m.groupValues[1] == "~" -> PumpTarget.SELF; m.groupValues[1] == "it" -> PumpTarget.TRIGGERING; else -> PumpTarget.ANOTHER
            }, keywords = kw)
            val target = when {
                "creatures" in m.groupValues[1] || "creature you control" in m.groupValues[1] && "target" !in m.groupValues[1] -> PumpTarget.ALL
                m.groupValues[1] == "~" -> PumpTarget.SELF; m.groupValues[1] == "it" -> PumpTarget.TRIGGERING; else -> PumpTarget.ANOTHER
            }
            return Pump(XN, XN, target, keywords = kw, scale = s)
        }
        rx("""another target ([a-z]+) (creature )?you control gets \+(\d+)/\+(\d+)""").find(text)?.let { m ->
            return Pump(m.groupValues[3].toInt(), m.groupValues[4].toInt(), PumpTarget.ANOTHER, singular(m.groupValues[1]).takeIf { it != "creature" }, kw)
        }
        rx("""target creature you control gets \+(\d+)/\+(\d+)""").find(text)?.let { m ->
            return Pump(m.groupValues[1].toInt(), m.groupValues[2].toInt(), PumpTarget.ANOTHER, null, kw)
        }
        rx("""(other )?([a-z]+ )?creatures you control get \+(\d+)/\+(\d+) (and gain [a-z ,]+ )?until end of turn""").find(text)?.let { m ->
            val tribe = m.groupValues[2].trim().takeIf { it.isNotEmpty() && it !in setOf("attacking", "blocking", "tapped", "untapped", "nontoken", "other", "all") }
            return Pump(m.groupValues[3].toInt(), m.groupValues[4].toInt(), PumpTarget.ALL, tribe?.let { singular(it) }, kw)
        }
        if (rx("""double (the power and toughness of each creature you control|the power of each creature you control)""").containsMatchIn(text)) return Pump(0, 0, PumpTarget.ALL, doublePower = true, keywords = kw)
        if (rx("""double (its|~'s|target creature's) power""").containsMatchIn(text)) return Pump(0, 0, PumpTarget.TRIGGERING, doublePower = true, keywords = kw)
        rx("""(~|it|that creature|equipped creature|enchanted creature) gets \+(\d+)/\+(\d+)( and gains [a-z ,]+)? until end of turn""").find(text)?.let { m ->
            return Pump(m.groupValues[2].toInt(), m.groupValues[3].toInt(), if (m.groupValues[1] == "~") PumpTarget.SELF else PumpTarget.TRIGGERING, null, kw)
        }
        rx("""(up to one |another )?target (attacking )?creature( you control)? gets \+(\d+)/\+(\d+)""").find(text)?.let { m ->
            return Pump(m.groupValues[4].toInt(), m.groupValues[5].toInt(), PumpTarget.ANOTHER, null, kw)
        }
        // "All Zombies gain menace", "Dragon creatures get +1/+1", "attacking creatures get +1/+0 until end of turn".
        rx("""(?:^|\. |, )(?:all |each )?(?:other )?(attacking |other |blocking )?([a-z]+?)s? (creatures? |tokens? )?(you control )?(?:with flying )?(?:gets? \+(\d+)/\+(\d+)|gains? ([a-z ,]+?))( and gains? [a-z ,]+?)? until end of turn""").find(text)?.let { m ->
            val w = m.groupValues[2]
            if (w in setOf("target", "it", "that", "you", "player", "opponent", "card", "~")) return@let
            val tribe = if (w == "creature" || w == "those" || w == "attacking") null else singular(w)
            val kws = Kw.of(m.groupValues[7] + m.groupValues[8])
            if (m.groupValues[5].isEmpty() && kws == 0) return@let
            return Pump(m.groupValues[5].toIntOrNull() ?: 0, m.groupValues[6].toIntOrNull() ?: 0, PumpTarget.ALL, tribe, kws)
        }
        rx("""(~|it|that creature|creatures you control|other creatures you control|another target attacking creature|target attacking creature|those creatures|attacking creatures you control|attacking creatures) gains? ([a-z ,]+?) until end of turn""").find(text)?.takeIf { Kw.of(it.groupValues[2]) != 0 }?.let { m ->
            val target = when {
                m.groupValues[1].contains("creatures") -> PumpTarget.ALL; m.groupValues[1] == "~" -> PumpTarget.SELF
                m.groupValues[1].contains("target") -> PumpTarget.ANOTHER; else -> PumpTarget.TRIGGERING
            }
            return Pump(0, 0, target, null, Kw.of(m.groupValues[2]))
        }
        return null
    }

    internal fun trigger(line: String, permanent: Boolean): Trigger? = when {
        line.startsWith("at the beginning of combat on your turn") || line.startsWith("at the beginning of each combat") -> Trigger.COMBAT_START
        line.startsWith("at the beginning of combat on each opponent's turn") -> Trigger.OPP_UPKEEP
        rx("""^when(ever)? you cast this spell|^when ~ is turned face up|^when ~ enters or is turned face up|^whenever ~ mutates""").containsMatchIn(line) -> if (permanent) Trigger.ETB else Trigger.CAST
        rx("""^whenever ~ is dealt damage""").containsMatchIn(line) -> Trigger.SELF_DAMAGED
        rx("""^whenever you create (one or more |a )?(creature )?tokens?|^whenever you create or sacrifice a token""").containsMatchIn(line) -> Trigger.TOKEN_CREATED
        rx("""^whenever (a|another) (nontoken )?(creature )?token (you control )?leaves the battlefield|^whenever an? (enchantment|artifact) you control is put into a graveyard from the battlefield""").containsMatchIn(line) -> Trigger.OWN_DIES
        rx("""^whenever (a|another) ([a-z]+) dies""").containsMatchIn(line) && !rx("""you control|opponent""").containsMatchIn(line.substringBefore(",")) -> Trigger.DIES
        Regex("""^when ~ becomes level \d+""").containsMatchIn(line) -> Trigger.LEVEL_UP
        rx("""^whenever ~ blocks( a creature)?,""").containsMatchIn(line) -> Trigger.SELF_BLOCKS
        rx("""^whenever (enchanted player is attacked|a player attacks enchanted player|a creature attacks enchanted player|enchanted player attacks)""").containsMatchIn(line) -> Trigger.CURSE
        rx("""^whenever (an|another) enchantment (you control )?enters|^constellation""").containsMatchIn(line) -> Trigger.ENCHANTMENT_ETB
        rx("""^whenever (a creature attacks you|one or more creatures (an opponent controls )?attack you|a creature an opponent controls attacks|an opponent attacks you)""").containsMatchIn(line) -> Trigger.OPP_ATTACKS
        rx("""^whenever (a player attacks|one or more creatures attack|a creature attacks one of your opponents|an opponent attacks another)""").containsMatchIn(line) -> Trigger.ANY_ATTACK
        rx("""^when(ever)? you cycle (~|or discard ~)""").containsMatchIn(line) -> Trigger.CYCLED
        rx("""^whenever you (discard|cycle or discard)""").containsMatchIn(line) -> Trigger.DISCARD
        rx("""^when ~ becomes monstrous""").containsMatchIn(line) -> Trigger.MONSTROUS
        rx("""^whenever you surveil""").containsMatchIn(line) -> Trigger.SURVEIL
        rx("""^whenever your commander (enters|attacks|deals combat damage)""").containsMatchIn(line) -> Trigger.COMMANDER
        rx("""^whenever (a|another) (nontoken )?creature (an opponent controls|you don't control) enters""").containsMatchIn(line) -> Trigger.OPP_CREATURE_ETB
        rx("""^whenever (a player|an opponent) sacrifices""").containsMatchIn(line) -> Trigger.SACRIFICE_ANY
        rx("""^whenever ~ becomes untapped""").containsMatchIn(line) -> Trigger.UPKEEP
        rx("""^whenever (a|another) (nontoken )?creature (an opponent controls|you don't control) dies|^whenever another creature leaves the battlefield""").containsMatchIn(line) -> Trigger.DIES
        rx("""^whenever (an|another) (equipment|artifact creature) (you control )?enters""").containsMatchIn(line) -> Trigger.ARTIFACT_ETB
        rx("""^when(ever)? ~ deals (combat )?damage""").containsMatchIn(line) -> Trigger.COMBAT_DAMAGE
        rx("""^whenever (~ or )?(a|another|one or more) (other )?(nontoken )?([a-z]+) you control (dies|die)\b""").containsMatchIn(line) &&
            !rx("""opponent|player""").containsMatchIn(line.substringBefore(",")) -> Trigger.OWN_DIES
        rx("""^whenever a \+1/\+1 counter is put on ~""").containsMatchIn(line) -> Trigger.COUNTERS_PUT
        rx("""^whenever (equipped|enchanted) creature (attacks|becomes tapped)""").containsMatchIn(line) -> Trigger.ATTACHED_ATTACKS
        rx("""^when(ever)? (equipped|enchanted) creature (dies|is put into a graveyard)""").containsMatchIn(line) -> Trigger.ATTACHED_DIES
        rx("""^whenever (equipped|enchanted) creature deals (combat )?damage""").containsMatchIn(line) -> Trigger.ATTACHED_DAMAGE
        rx("""^whenever you draw (a|your [a-z]+) card""").containsMatchIn(line) -> Trigger.DRAW
        rx("""^whenever (a|another) player casts""").containsMatchIn(line) -> Trigger.ANY_CAST
        rx("""^whenever an opponent casts""").containsMatchIn(line) -> Trigger.OPP_CAST
        rx("""^whenever you gain life""").containsMatchIn(line) -> Trigger.LIFEGAIN
        rx("""^at the beginning of each opponent's (upkeep|draw step|end step)""").containsMatchIn(line) -> Trigger.OPP_UPKEEP
        rx("""^at the beginning of (each upkeep|each player's upkeep|each player's draw step)""").containsMatchIn(line) -> Trigger.EACH_UPKEEP
        rx("""^at the beginning of (the|each|your) (next )?(end step|postcombat main phase|second main phase)|^at the beginning of each player's end step""").containsMatchIn(line) -> Trigger.END_STEP
        rx("""^whenever one or more \+1/\+1 counters are put on""").containsMatchIn(line) -> Trigger.COUNTERS_PUT
        rx("""^whenever an opponent (sacrifices|discards)""").containsMatchIn(line) -> Trigger.OPP_LOSES_CARD
        rx("""^whenever you sacrifice""").containsMatchIn(line) -> Trigger.SACRIFICE
        rx("""^~ enters with""").containsMatchIn(line) -> Trigger.ETB
        rx("""^when(ever)? ~ (is put into a graveyard from the battlefield|leaves the battlefield)""").containsMatchIn(line) -> Trigger.SELF_DIES
        rx("""^whenever ~ (becomes tapped|attacks or blocks|blocks or becomes blocked|becomes blocked)""").containsMatchIn(line) -> Trigger.SELF_ATTACK
        rx("""^whenever ~ deals (combat )?damage to (a player|an opponent|one or more players)""").containsMatchIn(line) -> Trigger.COMBAT_DAMAGE
        rx("""^whenever (a|another) creature (an opponent controls|you don't control) dies""").containsMatchIn(line) -> Trigger.DIES
        rx("""^whenever (a|another) ([a-z]+ )?creature you control explores""").containsMatchIn(line) -> Trigger.EXPLORES
        rx("""^whenever (another|a|an) ([a-z]+) (creature |token )?you control (with [^,]+? )?attacks""").containsMatchIn(line) -> Trigger.CREATURE_ATTACKS
        rx("""^(when|whenever) ~ (enters|or another [a-z ]+ enters)""").containsMatchIn(line) -> Trigger.ETB
        rx("""^when(ever)? ~ dies""").containsMatchIn(line) -> Trigger.SELF_DIES
        "whenever you attack" in line -> Trigger.ATTACK
        rx("""^whenever ~ deals combat damage to a player""").containsMatchIn(line) -> Trigger.COMBAT_DAMAGE
        rx("""^when(ever)? ~ (attacks|deals combat damage)""").containsMatchIn(line) -> Trigger.SELF_ATTACK
        // Rooms: the second door is unlocked later; played as happening when it enters.
        rx("""^when you unlock this door""").containsMatchIn(line) -> Trigger.ETB
        rx("""^whenever (a|an|another|one or more) ([a-z]+ )?([a-z]+ )?(creatures? )?you control (of the chosen type )?(with [^,]+? )?deals? combat damage to (a player|one of your opponents|an opponent)""").containsMatchIn(line) ||
            rx("""^whenever a creature deals combat damage to one of your opponents""").containsMatchIn(line) -> Trigger.CREATURE_COMBAT_DAMAGE
        rx("""^whenever (a|another|one or more) [a-z ]*creatures? you control (with [a-z0-9 +/,]+? )?attack""").containsMatchIn(line) -> Trigger.ATTACK
        rx("""^at the beginning of (your|each) (upkeep|draw step|precombat main phase|first main phase|combat)""").containsMatchIn(line) -> Trigger.UPKEEP
        rx("""^at the beginning of (your|each) end step""").containsMatchIn(line) -> Trigger.END_STEP
        line.startsWith("landfall") || rx("""^whenever (a|one or more) lands? (you control )?enters?""").containsMatchIn(line) -> Trigger.LANDFALL
        rx("""^whenever you cast (a|an|your first|your second)? ?(noncreature|instant or sorcery|instant|sorcery|historic|artifact|enchantment)""").containsMatchIn(line) -> Trigger.CAST_NONCREATURE
        line.startsWith("whenever you cast") -> Trigger.CAST_SPELL
        rx("""^whenever (one or more |an |another )?(nontoken )?artifacts? (you control )?(enters?|are put)""").containsMatchIn(line) ||
            rx("""^whenever one or more artifacts enter""").containsMatchIn(line) -> Trigger.ARTIFACT_ETB
        rx("""^whenever (another |a |one or more )?(other )?(nontoken )?[a-z ]*creatures? (you control )?(of the chosen type )?(with [a-z0-9 ]+? )?enters?""").containsMatchIn(line) ||
            rx("""^whenever (another|a|one or more) (nontoken )?([a-z]+) you control enters?""").containsMatchIn(line) -> Trigger.CREATURE_ETB
        rx("""^whenever (~ or )?(a|another|one or more) (other )?(nontoken )?[a-z ]*creatures? you control (with(out)? [^,]+? )?(dies|die|is put into)""").containsMatchIn(line) -> Trigger.OWN_DIES
        rx("""^whenever (~ or )?(a|another|one or more) (other )?(nontoken )?creatures? (dies|die)""").containsMatchIn(line) -> Trigger.DIES
        // "Whenever an opponent casts…" (Rhystic Study and the like): about once a round.
        line.startsWith("whenever an opponent") -> Trigger.UPKEEP
        !permanent -> Trigger.CAST
        // Static abilities that only matter when they're checked (Laboratory Maniac).
        "you win the game" in line -> Trigger.UPKEEP
        // Clones: "You may have ~ enter as a copy of any creature on the battlefield."
        "enter as a copy of" in line -> Trigger.ETB
        else -> null
    }

    private val keywordLine = Regex(
        """^(flying|first strike|double strike|deathtouch|haste|hexproof|indestructible|lifelink|menace|reach|trample|vigilance|""" +
            """defender|shroud|flash|prowess|ward|protection from|partner|equip|enchant|changeling|convoke|cascade|infect|toxic|""" +
            """affinity|delve|storm|crew|kicker|flashback|cycling|landcycling|ninjutsu|choose a background|friends forever)[^.]*$""",
    )
    /** Keywords that become triggered or activated abilities. Null when [line] isn't one of them. */
    fun keywordAbility(line: String): Pair<List<Ability>, List<Activated>>? {
        val l = line.trim().removeSuffix(".")
        fun num(): Int = rx("""(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        fun one(t: Trigger, vararg e: Effect, evolve: Boolean = false, optional: ManaCost? = null) =
            listOf(Ability(t, e.toList(), evolve = evolve, optionalCost = optional)) to emptyList<Activated>()
        return when {
            l == "prowess" -> one(Trigger.CAST_NONCREATURE, Pump(1, 1, PumpTarget.SELF))
            l == "extort" -> one(Trigger.CAST_SPELL, LoseLife(1, each = true, gainBack = true), optional = ManaCost(emptyList(), 1))
            l.startsWith("annihilator ") -> one(Trigger.SELF_ATTACK, Edict(num(), Who.DEFENDER, "permanent"))
            l == "battle cry" -> one(Trigger.SELF_ATTACK, Pump(1, 0, PumpTarget.ALL))
            l == "dethrone" || l == "training" -> one(Trigger.SELF_ATTACK, Counters(1, all = false, self = true))
            l == "mentor" -> one(Trigger.SELF_ATTACK, Counters(1, all = false, self = false))
            l == "melee" -> one(Trigger.SELF_ATTACK, Pump(1, 1, PumpTarget.SELF))
            l.startsWith("bushido ") -> one(Trigger.SELF_ATTACK, Pump(num(), num(), PumpTarget.SELF))
            l == "myriad" -> one(Trigger.SELF_ATTACK, Myriad)
            l.startsWith("mobilize ") -> one(Trigger.SELF_ATTACK, Damage(num(), each = false))
            l == "evolve" -> one(Trigger.CREATURE_ETB, Counters(1, all = false, self = true), evolve = true)
            l.startsWith("afterlife ") -> one(Trigger.SELF_DIES, MakeTokens(num(), TokenKind.CREATURE, "Spirit", 1, 1, Kw.FLYING))
            l.startsWith("fabricate ") -> one(Trigger.ETB, Counters(num(), all = false, self = true))
            l == "riot" || l == "unleash" -> one(Trigger.ETB, Counters(1, all = false, self = true))
            rx("""^(graft|modular|bloodthirst|tribute) \d+""").matches(l) -> one(Trigger.ETB, Counters(num(), all = false, self = true))
            l == "sunburst" -> one(Trigger.ETB, Counters(2, all = false, self = true))
            l.startsWith("devour ") -> one(Trigger.ETB, Devour(num()))
            l.startsWith("backup ") -> one(Trigger.ETB, Counters(num(), all = false, self = false))
            l == "living weapon" -> one(Trigger.ETB, Germ(0, 0, "Phyrexian Germ"))
            l == "for mirrodin!" -> one(Trigger.ETB, Germ(2, 2, "Rebel"))
            l.startsWith("job select") -> one(Trigger.ETB, Germ(1, 1, "Hero"))
            l == "ravenous" -> one(Trigger.ETB, Counters(XN, all = false, self = true))
            l == "exploit" -> emptyList<Ability>() to emptyList()
            // Vanishing N / fading N: gone after about N turns.
            rx("""^(vanishing|fading) \d+$""").matches(l) -> listOf(Ability(Trigger.ETB, listOf(Mark(num() + if (l.startsWith("fading")) 1 else 0))),
                Ability(Trigger.UPKEEP, listOf(Vanish))) to emptyList()
            l.startsWith("renown ") -> one(Trigger.COMBAT_DAMAGE, Counters(num(), all = false, self = true))
            l == "skip your draw step" -> emptyList<Ability>() to emptyList()
            l.startsWith("outlast ") || l.startsWith("level up ") -> emptyList<Ability>() to listOf(
                Activated(ManaCost.parse(l.substringAfter(' ').substringAfter("up ")), tap = l.startsWith("outlast"), sacrificeSelf = false,
                    effects = listOf(Counters(1, all = false, self = true)), sorcery = true))
            l.startsWith("scavenge ") -> emptyList<Ability>() to listOf(
                Activated(ManaCost.parse(l.removePrefix("scavenge").trim()), tap = false, sacrificeSelf = false,
                    effects = listOf(Counters(POWER, all = false, self = false)), fromGraveyard = true, sorcery = true))
            else -> null
        }
    }

    /** Keywords the card model or game loop handles on the card itself (alternative costs, graveyard casting…). */
    val cardKeyword = Regex(
        """^(cycling|[a-z]+cycling|basic landcycling|kicker|multikicker|flashback|retrace|jump-start|escape|unearth|embalm|eternalize|encore|disturb|""" +
            """evoke|rebound|overload|convoke|improvise|delve|affinity for|undaunted|squad|demonstrate|storm|umbra armor|totem armor|split second|harmonize|""" +
            """persist|undying|echo|buyback|offspring|replicate|crew|fear|intimidate|[a-z]+walk|horsemanship|shadow|skulk|decayed|wither|flash|exalted|suspend)\b""",
    )

    /** Keywords and lines that don't change anything in this model (casting it face up, commander rules, colour). */
    val noEffectLine = Regex(
        """^(devoid|doctor's companion|partner( with [^.]+)?|~ can be your commander|companion — |morph|megamorph|disguise|ascend|reconfigure|""" +
            """prototype|blitz|dash|plot|warp|more than meets the eye|~ attacks each combat if able|~ attacks or blocks each combat if able|""" +
            """~ can block an additional creature|~ can block any number of creatures|you may look at the top card of your library|""" +
            """start your engines!|bargain|gift a [a-z]+|spree|saddle|station|this spell can't be copied|foretell|prowl|spectacle|emerge|miracle|madness|""" +
            """bestow|mutate|fuse|dredge|surge|ninjutsu|commander ninjutsu|play with the top card of your library revealed|~ is the chosen type|""" +
            """~ escapes with|damage can't be prevented|you may cast this spell as though it had flash|cast this spell only|""" +
            """you may cast [a-z ]+ spells as though they had flash|~ can't be blocked except by|~ can block only creatures with flying|escalate|""" +
            """[^.]*roll (the|one or more) planar di(e|ce)|will of the planeswalkers|[^.]*\bplaneswalk\b|time travel|read ahead|daybound|nightbound|""" +
            """when ~ dies[^,]*, (you may )?put it (on the bottom of|into) its owner's library|spells your opponents cast that target ~ cost|cumulative upkeep|""" +
            """reinforce \d+|awaken \d+|[a-z]+ offering|champion an? [a-z]+|flanking|time travel|you may choose not to untap ~|counters remain on ~|""" +
            """when ~ is put into a graveyard from anywhere, shuffle it into its owner's library|creatures named [a-z ,']+ can't attack you)\b""",
    )

    /** The cost part of an activated ability ("{2}, {T}, Sacrifice an artifact"). */
    private val activatedCost = Regex(
        """^(\{[^}]+\}|,|\s|sacrifice ~|sacrifice (a|an|another|two|three|x) [a-z ]+?|tap (an|two|three|four|five|ten|x|\d+) untapped [a-z ]+|""" +
            """pay (\d+|x) life|discard (a|one|two|x) cards?( at random)?|discard your hand|remove (a|an|one|two|three|four|five|six|seven|ten|x|\d+|all) [a-z0-9+/ -]+? counters? from ~|""" +
            """exert ~|exile ~( from your graveyard)?|exile (a|an|one|two|three|x|\d+) [a-z ]*cards? from your graveyard|return (a|an) [a-z ]+ you control to its owner's hand|""" +
            """put (a|an) [a-z0-9+/ -]+ counter on ~|tap ~|untap ~|collect evidence \d+|mill (a|one|two|three) cards?|forage|reveal [a-z ]+ from your hand|""" +
            """pay (\{e\})+|pay (a|an|one|two|three|four|five|six|seven|eight|x|\d+) \{e\}|pay [a-z]+ \{e\}|""" +
            """remove (a|an|one|two|three|x|\d+) [a-z0-9+/ -]*counters? from (a|an|another) [a-z ]+ you control|discard (a|an) [a-z ]+ card|""" +
            """return ~ to its owner's hand|return (a|two|three) (lands?|[a-z ]+) you control to (its|their) owners?'s? hands?|sacrifice ~ and [a-z ]+|""" +
            """sacrifice (four|five|six|seven|eight|ten|thirteen|x) [a-z ]+|sacrifice (another|a|an) [a-z]+(, [a-z]+)*,? (and/)?or [a-z]+|""" +
            """untap (a|an|one|two) tapped [a-z ]+ you control|put ~ on the bottom of its owner's library|""" +
            """exile (a|an|one|two|three) [a-z ]+ cards? from a single graveyard|put a [a-z ]+ card exiled with ~ into its owner's graveyard)+$""",
    )
    private val chapter = Regex("""^((?:i|ii|iii|iv|v|vi)(?:, (?:i|ii|iii|iv|v|vi))*) — (.+)$""")
    private val romans = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6)

    /**
     * Reads [oracle]. [roleLine] says whether a line is already handled by the role-based effects (removal, wipes,
     * counters, ramp, tutors, anthems…) so it counts as understood.
     */
    fun parse(name: String, oracle: String?, permanent: Boolean, roleLine: (String) -> Boolean): Parsed {
        if (oracle.isNullOrBlank()) return Parsed(emptyList(), emptyList(), emptyList(), emptyList(), Coverage(emptyList(), emptyList()))
        val abilities = mutableListOf<Ability>()
        val activated = mutableListOf<Activated>()
        val loyalty = mutableListOf<Loyalty>()
        val chapters = sortedMapOf<Int, MutableList<Effect>>()
        val understood = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val face = oracle.substringBefore("\n//").substringBefore(" // ")
        val text = normalise(name, face)
        // Modal spells: only the first mode(s) the reader understands are used, not every mode at once.
        val modes = Regex("""choose (one|two|three|one or more|one or both|any number)""").find(text)?.groupValues?.get(1)
        val modeLimit = when (modes) { null -> Int.MAX_VALUE; "two" -> 2; "three" -> 3; "one or more", "one or both", "any number" -> 2; else -> 1 }
        var modesUsed = 0
        /** A triggered ability whose effect is "choose one —": its modes belong to that trigger. */
        var modalTrigger: Ability? = null
        /** An activated ability whose effect is "choose one —": the first mode the reader understands is its effect. */
        var modalActivated: Activated? = null
        var level = 1
        for (raw in text.split('\n').map { it.trim() }.filter { it.isNotBlank() }) {
            rx("""^(?:\{[^}]+\})+: level (\d+)$""").find(raw)?.let { level = it.groupValues[1].toInt() }
            val isMode = raw.startsWith("•")
            val line = raw.removePrefix("•").trim()
            if (!isMode) { modalTrigger = null; modalActivated = null }
            if (modes != null && line.startsWith("choose ") && (line.endsWith("—") || "choose the same mode" in line || "you may choose both" in line ||
                    "you may choose" in line)) { understood += "modal: choose $modes"; continue }
            if (rx("""^as an additional cost to cast this spell, (you may )?(discard|sacrifice|pay|exile|tap|return|reveal|forage|collect evidence|behold)""").containsMatchIn(line) ||
                rx("""^this spell costs \{(\d+|x)\} (less|more) to cast""").containsMatchIn(line) ||
                rx("""rather than pay (this spell's|~'s) mana cost""").containsMatchIn(line) && !line.contains(':') && !rx("""^(whenever|when|at the beginning)""").containsMatchIn(line)) {
                understood += "cost: ${line.take(60)}"; continue
            }
            // Keywords: abilities they become, keywords the card model handles, and ones that change nothing here.
            if (!line.contains(':')) {
                val parts = line.removeSuffix(".").split(", ").map { it.trim() }
                val kws = parts.map { keywordAbility(it) }
                fun known(i: Int) = kws[i] != null || keywordLine.containsMatchIn(parts[i]) || cardKeyword.containsMatchIn(parts[i]) || noEffectLine.containsMatchIn(parts[i])
                if (kws.any { it != null } && parts.indices.all { known(it) }) {
                    kws.filterNotNull().forEach { (a, b) -> abilities += a; activated += b }
                    understood += "keyword: $line"; continue
                }
                if (cardKeyword.containsMatchIn(line) || noEffectLine.containsMatchIn(line)) { understood += "keyword: $line"; continue }
                if (line == "exile ~." && !permanent) { understood += "exiled as it resolves"; continue }
                if (rx("""^level \d+(-\d+|\+)$|^\d+/\d+$""").matches(line)) { understood += "level: $line (levels as +1/+1 counters)"; continue }
            }
            if (rx("""^when ~ exploits a creature""").containsMatchIn(line)) {
                val eff = effects(line)
                if (eff.isNotEmpty()) { abilities += Ability(Trigger.ETB, eff); understood += "exploit: " + eff.joinToString("; ") { it.describe() }; continue }
            }
            if (keywordLine.containsMatchIn(line) && !line.contains(':') ||
                line.startsWith("equip") || line.startsWith("crew") || line.startsWith("enchant ")) {
                understood += "keyword: $line"; continue
            }
            // Saga chapters.
            val ch = chapter.find(line)
            if (ch != null) {
                val eff = effects(ch.groupValues[2]).filterNot { it is ReturnDied }
                if (eff.isNotEmpty() || roleLine(ch.groupValues[2])) {
                    ch.groupValues[1].split(", ").forEach { r -> chapters.getOrPut(romans.getValue(r)) { mutableListOf() } += eff }
                    understood += "chapter ${ch.groupValues[1].uppercase()}: " + (eff.joinToString("; ") { it.describe() }.ifBlank { ch.groupValues[2].take(50) })
                } else missed += raw
                continue
            }
            // Planeswalker loyalty abilities: "+1: …", "-2: …", "0: …".
            val loy = Regex("""^([+-]?)(\d+|x): (.+)$""").find(line)
            if (loy != null) {
                val eff = effects(loy.groupValues[3]).filterNot { it is ReturnDied }
                val delta = (loy.groupValues[2].toIntOrNull() ?: 3) * (if (loy.groupValues[1] == "-") -1 else 1)
                if (eff.isNotEmpty() || roleLine(loy.groupValues[3])) {
                    loyalty += Loyalty(delta, eff)
                    understood += Loyalty(delta, eff).describe()
                } else missed += raw
                continue
            }
            // Activated ability: "cost: effect" (after any ability word: "grand summon — {t}: …").
            val act = line.replace(rx("""^[a-z0-9' ~!-]+ — (?=\{|pay |sacrifice |remove |discard |exile |tap |return )"""), "")
            val colon = act.indexOf(':')
            if (colon in 1..90 && activatedCost.matches(act.substring(0, colon))) {
                val costText = act.substring(0, colon)
                val effectText = act.substring(colon + 1)
                if (rx("""\badd \{|\badd one mana|\badd (two|three|x) mana|\badd an amount""").containsMatchIn(effectText)) { understood += "mana ability"; continue }
                if (rx("""roll the planar die""").containsMatchIn(effectText)) { understood += "activated: planar die (no planar deck in these games)"; continue }
                // "{2}{U}{U}: Return ~ to its owner's hand": used like regeneration, to save it from removal.
                if (rx("""^\s*return ~ to its owner's hand\.?$""").containsMatchIn(effectText) && !rx("""sacrifice|exile ~""").containsMatchIn(costText)) {
                    activated += Activated(ManaCost.parse(costText.replace("{t}", "").replace("{e}", ""), 0), tap = "{t}" in costText, sacrificeSelf = false, effects = emptyList(),
                        lifeCost = rx("""pay (\d+) life""").find(costText)?.groupValues?.get(1)?.toIntOrNull() ?: 0, regenerate = true)
                    understood += "activate (${costText.trim()}): return it to hand to save it from removal"
                    continue
                }
                val eff = effects(effectText.trim()).filterNot { it is ReturnDied }.takeUnless { e -> e.isNotEmpty() && e.all { it == ReturnToHand } && "from your graveyard" !in effectText }.orEmpty()
                val modal = eff.isEmpty() && rx("""^\s*choose (one|two|one or more)( —|\.)""").containsMatchIn(effectText)
                if (eff.isNotEmpty() || modal || rx("""^\s*regenerate ~""").containsMatchIn(effectText)) {
                    val sac = Regex("sacrifice (a|an|another|two|three) ([a-z]+)").find(costText)?.groupValues?.get(2)
                    val tapN = Regex("tap (an|two|three|four|five|ten|x|\\d+) untapped ([a-z ]+?)( you control)?$").find(costText.substringAfterLast(", "))
                    val a = Activated(
                        ManaCost.parse(costText.replace("{t}", "").replace("{q}", "").replace("{e}", ""), 0),
                        tap = "{t}" in costText || "tap ~" in costText, sacrificeSelf = rx("""sacrifice ~|exile ~(?! from)""").containsMatchIn(costText), effects = eff,
                        sacrificeCreature = sac != null,
                        sacrificeTribe = sac?.let { singular(it) }?.takeIf { it != "creature" && it != "token" },
                        tapCreatures = tapN?.let { n(it.groupValues[1]).let { v -> if (v == XN) 3 else v } } ?: 0,
                        tapTribe = tapN?.groupValues?.get(2)?.split(' ')?.last()?.let { singular(it) }?.takeIf { it != "creature" },
                        removeCounter = rx("""remove (a|an|one) \+1/\+1 counter from ~""").containsMatchIn(costText),
                        removeMarks = rx("""remove (a|an|one|two|three|four|five|six|seven|ten|x|\d+) ([a-z]+) counters? from ~""").find(costText)?.let { m ->
                            if (m.groupValues[2] == "+1/+1") 0 else n(m.groupValues[1]).let { v -> if (v == XN) 1 else v }
                        } ?: 0,
                        fromGraveyard = "exile ~ from your graveyard" in costText || ("from your graveyard" in effectText && rx("""(return|put) ~""").containsMatchIn(effectText)),
                        lifeCost = rx("""pay (\d+) life""").find(costText)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        discardCost = rx("""discard (a|one) card""").containsMatchIn(costText),
                        regenerate = rx("""^\s*regenerate ~""").containsMatchIn(effectText),
                        sorcery = "activate only as a sorcery" in effectText,
                    )
                    activated += a
                    if (modal) modalActivated = a else understood += a.describe()
                } else if (roleLine(effectText)) understood += "activated: ${effectText.trim()}" else missed += raw
                continue
            }
            if (isMode && modes != null) {
                val eff = effects(line).filterNot { it is ReturnDied }
                val mt = modalTrigger
                val ma = modalActivated
                when {
                    ma != null -> when {
                        eff.isNotEmpty() && ma.effects.isEmpty() -> {
                            val withEff = ma.copy(effects = eff)
                            activated[activated.indexOf(ma)] = withEff
                            modalActivated = withEff
                            understood += withEff.describe()
                        }
                        eff.isNotEmpty() || roleLine(line) -> understood += "mode: ${line.take(60)}"
                        else -> missed += raw
                    }
                    eff.isNotEmpty() && mt != null -> {
                        // A triggered "choose one": the first mode the reader understands is the one used.
                        if (mt.effects.isEmpty()) {
                            abilities.remove(mt)
                            val withEff = mt.copy(effects = eff)
                            abilities += withEff
                            modalTrigger = withEff
                        }
                        understood += "mode: " + eff.joinToString("; ") { it.describe() }
                    }
                    eff.isNotEmpty() && modesUsed < modeLimit -> {
                        modesUsed++
                        abilities += Ability(if (permanent) Trigger.ETB else Trigger.CAST, eff)
                        understood += "mode: " + eff.joinToString("; ") { it.describe() }
                    }
                    eff.isNotEmpty() || roleLine(line) -> understood += "mode: ${line.take(60)}"
                    else -> missed += raw
                }
                continue
            }
            val eminence = line.startsWith("eminence") || "is in the command zone or on the battlefield" in line
            val body = line.replace(rx("""^[a-z0-9 ,'~!-]+ — (?!\{)"""), "")
            val kicked = Regex("""\bif (~|it|this spell|that creature) was (kicked|bargained)|\bif (~|it|this spell)'s additional cost was paid""").containsMatchIn(body)
            val known = trigger(body, permanent) ?: if (kicked) (if (permanent) Trigger.ETB else Trigger.CAST) else null
            // A repeatable event the model doesn't track: its effect happens about every other turn.
            val trig = known ?: if (permanent && body.startsWith("whenever ") && !rx("""^whenever [^,]*(you lose|loses the game)""").containsMatchIn(body)) Trigger.SOMETIMES else null
            // A trigger's effects are read from after its condition ("whenever you draw a card, …" isn't itself a draw).
            val condition = Regex("""^(when|whenever|at the beginning of)\b[^,]*, """).find(body)
            val effText = if (trig != null && condition != null) body.substring(condition.range.last + 1) else body
            // "That many" refers to what the condition counted: damage dealt, or life gained.
            val cond = condition?.value.orEmpty()
            var eff = effects(when {
                trig == Trigger.LIFEGAIN -> effText.replace(rx("""\bthat much\b"""), "gainmany")
                "damage" in cond -> effText.replace(rx("""\bthat (many|much)\b"""), "dmgmany")
                else -> effText
            })
            // "Then if it has three or more doom counters, …": a milestone the model doesn't count, not something every turn.
            if (rx("""if (~|it) has (two|three|four|five|six|seven|ten|twenty|\d+) or more [a-z]+ counters on it""").containsMatchIn(body) && eff.none { it is Mark }) eff = emptyList()
            else if (rx("""if (~|it) has (two|three|four|five|six|seven|ten|twenty|\d+) or more [a-z]+ counters on it""").containsMatchIn(body)) eff = eff.filter { it is Mark }
            // "Return that card to the battlefield" only means the creature that died on a death trigger.
            if (eff.any { it is ReturnDied } && trig != Trigger.SELF_DIES && trig != Trigger.OWN_DIES && trig != Trigger.ATTACHED_DIES) eff = eff.filterNot { it is ReturnDied }
            // Untracked events only carry small effects: removal, steals or reanimation every other turn would be far too generous.
            if (trig == Trigger.SOMETIMES && eff.any { it is Remove || it is Sweep || it is Steal || (it is Edict && it.who != Who.SELF) || (it is Reanimate && it.toBattlefield) ||
                    it is ExtraTurn || it is ExtraCombat || it is WinGame || it is Wheel || it is FreeCast || it is MassReanimate || it is CopyCreature || it is GraveCast }) eff = emptyList()
            // "Whenever …, choose one —": the modes that follow are this trigger's effects.
            if (trig != null && eff.isEmpty() && rx("""choose (one|two|one or more|one or both|any number|up to one|up to two)( that hasn't been chosen)?( —|\.)?$""").containsMatchIn(body)) {
                val a = Ability(trig, emptyList(), oncePerTurn = "only once each turn" in line, fromCommandZone = eminence)
                abilities += a
                modalTrigger = a
                understood += "${trig.label}: choose a mode"
                continue
            }
            when {
                trig != null && eff.isNotEmpty() -> {
                    val optional = Regex("""you may pay ((?:\{[^}]+\})+)\. if you do""").find(body)?.let { ManaCost.parse(it.groupValues[1]) }
                    val tribe = if (trig == Trigger.CREATURE_ATTACKS || trig == Trigger.CREATURE_ETB || trig == Trigger.CREATURE_COMBAT_DAMAGE || trig == Trigger.OWN_DIES) tribeOf(body) else null
                    val minP = Regex("""with power (\d+) or greater""").find(body.substringBefore(","))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val maxP = Regex("""with power (\d+) or less""").find(body.substringBefore(","))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val a = Ability(trig, eff, oncePerTurn = "only once each turn" in line || rx("""your (first|second) [a-z ]*each turn""").containsMatchIn(body),
                        fromCommandZone = eminence, optionalCost = optional, tribe = tribe, minPower = minP, maxPower = maxP, kicked = kicked,
                        minLevel = rx("""^when ~ becomes level (\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: level)
                    // "Whenever a creature of the chosen type enters or attacks": both.
                    if (trig == Trigger.CREATURE_ETB && rx("""enters or attacks""").containsMatchIn(body)) abilities += a.copy(trigger = Trigger.CREATURE_ATTACKS)
                    if (trig == Trigger.ETB && rx("""^when(ever)? ~ enters or attacks""").containsMatchIn(body)) abilities += a.copy(trigger = Trigger.SELF_ATTACK)
                    abilities += a
                    understood += a.describe()
                    if (roleLine(line)) understood += "role: ${line.take(60)}"
                }
                trig == Trigger.SOMETIMES && !roleLine(line) -> missed += raw
                // "Creatures your opponents control attack each combat if able": they're tapped on your turn, as if goaded.
                trig == null && permanent && eff.isNotEmpty() && eff.all { it is TapCreatures && it.goad } -> {
                    abilities += Ability(Trigger.UPKEEP, eff); understood += "each turn: " + eff.joinToString("; ") { it.describe() }
                }
                // Control Magic: the aura takes the creature when it enters.
                trig == null && permanent && line.startsWith("you control enchanted") && eff.any { it is Steal } -> {
                    abilities += Ability(Trigger.ETB, eff.filterIsInstance<Steal>()); understood += "enters: " + eff.joinToString("; ") { it.describe() }
                }
                eff.isNotEmpty() && eff.all { it == TopOfLibrary } -> {
                    abilities += Ability(Trigger.UPKEEP, eff)
                    understood += eff.joinToString("; ") { it.describe() }
                }
                eff.isNotEmpty() && eff.all { it is WinGame } -> {
                    abilities += Ability(Trigger.UPKEEP, eff)
                    understood += eff.joinToString("; ") { it.describe() }
                }
                roleLine(line) -> understood += "role: ${line.take(70)}"
                else -> missed += raw
            }
        }
        activated.removeAll { it.effects.isEmpty() && !it.regenerate }
        val chapterList = if (chapters.isEmpty()) emptyList() else (1..chapters.lastKey()).map { chapters[it].orEmpty() }
        return Parsed(abilities, activated, loyalty, chapterList, Coverage(understood, missed))
    }
}
