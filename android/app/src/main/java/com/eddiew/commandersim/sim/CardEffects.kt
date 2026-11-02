package com.eddiew.commandersim.sim

/*
 * Rules-text reading for the quick model. Each card's text is read once, when a run starts, into triggered,
 * activated and loyalty abilities made of a few effect types. The game loop then only counts and compares numbers,
 * which keeps it fast. Text the reader doesn't understand is reported (see [Coverage]) rather than guessed at.
 */

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
    )

    /** Bits for keywords named in [text] (a "with …" clause or Scryfall's keyword list). */
    fun of(text: String): Int = words.fold(0) { m, (w, bit) -> if (Regex("\\b$w\\b").containsMatchIn(text)) m or bit else m }

    fun names(bits: Int): List<String> = words.filter { bits and it.second != 0 }.map { it.first }.distinct()
}

/** "X" in an effect: the X paid for the spell (or 2 when there's no X to pay). */
const val XN = -1
/** "That many" after combat damage: the damage just dealt. */
const val DMG = -2
/** "That many" after gaining life: life gained this turn. */
const val LIFE_GAINED = -3

enum class ScaleKind(val label: String) {
    CREATURES("creature you control"), ARTIFACTS("artifact you control"), LANDS("land you control"),
    LANDS_OF_COLOR("land of that type"), TRIBE("creature of that type"), OPPONENTS("opponent"), DEVOTION("devotion"),
    HAND("card in hand"), ENCHANTMENTS("enchantment you control"), TOKENS("token you control"),
}

/** "For each …" / "equal to …" multipliers, counted when the effect happens. */
data class Scale(val kind: ScaleKind, val color: Int = 0, val tribe: String? = null) {
    fun describe() = " for each ${tribe ?: kind.label}"
}

sealed interface Effect {
    fun describe(): String
}

private fun amount(n: Int) = when (n) { XN -> "X"; DMG -> "that many"; LIFE_GAINED -> "life gained this turn"; else -> "$n" }

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

data class Draw(val n: Int, val scale: Scale? = null) : Effect { override fun describe() = "draw ${amount(n)}" + scale?.describe().orEmpty() }
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
data class Counters(val n: Int, val all: Boolean, val self: Boolean, val scale: Scale? = null) : Effect {
    override fun describe() = "+1/+1 counter ×${amount(n)} on " + when { all -> "each creature you control"; self -> "itself"; else -> "a creature" } +
        scale?.describe().orEmpty()
}

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
) {
    fun describe() = (if (fromCommandZone) "eminence · " else "") + trigger.label +
        (tribe?.let { " ($it)" } ?: "") + (if (oncePerTurn) " (once a turn)" else "") +
        (optionalCost?.let { " · pay ${it.total}" } ?: "") + ": " + effects.joinToString("; ") { it.describe() }
}

enum class PumpTarget { SELF, ANOTHER, ALL, TRIGGERING }

/** "+N/+N until end of turn" (and keywords), on itself, another creature (of a type), all your creatures, or the attacker. */
data class Pump(
    val power: Int, val toughness: Int, val target: PumpTarget, val tribe: String? = null, val keywords: Int = 0,
    /** "+X/+X where X is its power". */
    val doublePower: Boolean = false,
) : Effect {
    override fun describe() = (if (doublePower) "double power" else "+$power/+$toughness") +
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

data class Activated(
    val cost: ManaCost, val tap: Boolean, val sacrificeSelf: Boolean, val effects: List<Effect>,
    /** Cost includes sacrificing another creature (paid with a small token, of [sacrificeTribe] if set). */
    val sacrificeCreature: Boolean = false,
    val sacrificeTribe: String? = null,
    /** "Tap ten untapped Elves you control": how many, and which type. */
    val tapCreatures: Int = 0,
    val tapTribe: String? = null,
) {
    fun describe() = "activate (${cost.total} mana${if (tap) ", tap" else ""}${if (sacrificeSelf) ", sacrifice it" else ""}" +
        "${if (sacrificeCreature) ", sacrifice a ${sacrificeTribe ?: "creature"}" else ""}${if (tapCreatures > 0) ", tap $tapCreatures ${tapTribe ?: "creature"}s" else ""}): " +
        effects.joinToString("; ") { it.describe() }
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
        "seven" to 7, "eight" to 8, "ten" to 10, "x" to XN, "that many" to 2, "dmgmany" to DMG)
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
        var t = oracle.lowercase().replace(Regex("""\([^)]*\)"""), "").replace('−', '-')
        val full = name.substringBefore(" // ").lowercase()
        val short = full.substringBefore(",").trim()
        t = t.replace(full, "~")
        if (short.length >= 4) t = t.replace(Regex("\\b${Regex.escape(short)}\\b"), "~")
        // "Hakbal of the Surging Soul" is just "Hakbal" in its own text.
        val first = full.substringBefore(" of ").substringBefore(" the ").trim()
        if (first != full && first.length >= 4) t = t.replace(Regex("\\b${Regex.escape(first)}\\b"), "~")
        return t.replace("this creature", "~").replace("enters the battlefield", "enters").replace("enter the battlefield", "enter")
    }

    private val colorWords = mapOf("white" to ManaColor.W, "blue" to ManaColor.U, "black" to ManaColor.B, "red" to ManaColor.R, "green" to ManaColor.G)
    private val basicTypes = mapOf("plains" to ManaColor.W, "island" to ManaColor.U, "swamp" to ManaColor.B, "mountain" to ManaColor.R, "forest" to ManaColor.G)

    /** "For each …" / "equal to …" in [text], if any. */
    fun scale(text: String): Scale? {
        Regex("""devotion to ([a-z]+)( and ([a-z]+))?""").find(text)?.let { m ->
            return Scale(ScaleKind.DEVOTION, (colorWords[m.groupValues[1]] ?: 0) or (colorWords[m.groupValues[3]] ?: 0))
        }
        if (Regex("""(equal to|x is) the number of cards in your hand""").containsMatchIn(text)) return Scale(ScaleKind.HAND)
        if ("for each opponent" in text) return Scale(ScaleKind.OPPONENTS)
        val m = Regex("""(?:for each|equal to the number of|x is the number of) (?:other )?([a-z]+?)s? (?:creatures? )?you control""").find(text) ?: return null
        return when (val w = m.groupValues[1]) {
            "creature" -> Scale(ScaleKind.CREATURES)
            "artifact" -> Scale(ScaleKind.ARTIFACTS)
            "enchantment" -> Scale(ScaleKind.ENCHANTMENTS)
            "land" -> Scale(ScaleKind.LANDS)
            "permanent" -> Scale(ScaleKind.CREATURES)
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
        val text = if ("combat damage" in raw) raw.replace("that many", "dmgmany") else raw
        val out = mutableListOf<Effect>()
        val sc = scale(text)
        fun scaled(base: Int) = if (sc != null && (base == 1 || base == XN)) sc else null
        // Tokens: one "create …" clause at a time.
        Regex("""create ([^.;]*?tokens?)([^.;]*)""").findAll(text).forEach { m ->
            val clause = m.groupValues[1]
            val rest = m.groupValues[2]
            if ("copy" in clause || "copies" in clause) return@forEach
            val count = n(Regex("""^(a|an|one|two|three|four|five|six|x|that many|dmgmany|\d+)\b""").find(clause)?.value)
            val kind = when {
                "treasure" in clause -> TokenKind.TREASURE
                "food" in clause -> TokenKind.FOOD
                "clue" in clause -> TokenKind.CLUE
                "creature" in clause || Regex("""\d+/\d+""").containsMatchIn(clause) -> TokenKind.CREATURE
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
        Regex("""draws? (a|an|one|two|three|four|five|seven|x|\d+) cards?""").find(text)?.let {
            if (!Regex("""(opponent|each player) draws""").containsMatchIn(text)) out += Draw(n(it.groupValues[1]), scaled(n(it.groupValues[1])))
        }
        val gainBack = Regex("""you gain (life equal to the life lost|that much life)""").containsMatchIn(text)
        Regex("""each opponent loses (\d+|x|one|two|three) life""").find(text)?.let { out += LoseLife(n(it.groupValues[1]), each = true, scale = scaled(n(it.groupValues[1])), gainBack = gainBack) }
            ?: Regex("""target (player|opponent) loses (\d+|x|one|two|three) life""").find(text)?.let { out += LoseLife(n(it.groupValues[2]), each = false, scale = scaled(n(it.groupValues[2]))) }
        Regex("""deals? (\d+|x) damage to (each opponent|each player|target player|target opponent|any target|target player or planeswalker)""").find(text)?.let {
            out += Damage(n(it.groupValues[1]), each = it.groupValues[2].startsWith("each"), scale = scaled(n(it.groupValues[1])))
        }
        Regex("""you gain (\d+|x|one|two|three) life""").find(text)?.let { out += GainLife(n(it.groupValues[1]), scaled(n(it.groupValues[1]))) }
        Regex("""put (a|an|one|two|three|x|\d+) \+1/\+1 counters? on (each (other )?creature you control|each creature|target creature( you control)?|target [a-z]+ you control|~|it|up to one target creature|another target creature)""")
            .find(text)?.let {
                val where = it.groupValues[2]
                out += Counters(n(it.groupValues[1]), all = where.startsWith("each"), self = where == "~" || where == "it", scale = scaled(n(it.groupValues[1])))
            }
        Regex("""~ enters with (a|one|two|three|four|x|\d+) \+1/\+1 counters?""").find(text)?.let {
            out += Counters(n(it.groupValues[1]), all = false, self = true, scale = scaled(n(it.groupValues[1])))
        }
        if (Regex("""that player mills that many cards""").containsMatchIn(text)) out += Mill(DMG, MillTarget.DAMAGED)
        else Regex("""(each opponent|target player|target opponent|you) mills? (a|one|two|three|four|five|ten|x|\d+) cards?""").find(text)?.let {
            out += Mill(n(it.groupValues[2]), when (it.groupValues[1]) { "you" -> MillTarget.SELF; "each opponent" -> MillTarget.EACH_OPPONENT; else -> MillTarget.OPPONENT })
        } ?: Regex("""^mill (a|one|two|three|four|five|x|\d+) cards?""").find(text)?.let { out += Mill(n(it.groupValues[1]), MillTarget.SELF) }
        Regex("""(each opponent|target player|target opponent) gets? (a|one|two|three|x|\d+) poison counters?""").find(text)?.let {
            out += Poison(n(it.groupValues[2]), each = it.groupValues[1] == "each opponent")
        }
        if (Regex("""\bproliferate\b""").containsMatchIn(text)) out += Proliferate
        if (Regex("""when ~ dies, return it to its owner's hand""").containsMatchIn(text)) out += ReturnToHand
        Regex("""exile target creature card from a graveyard\. create a token that's a copy of it(?:, except it's a (\d+)/(\d+) [a-z]+ ([a-z]+))?""").find(text)?.let { m ->
            out += GraveyardCopy(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].ifBlank { null })
        }
        if (Regex("""opponent's graveyard[^.]*\. put it onto the battlefield under your control|from an opponent's graveyard onto the battlefield under your control""").containsMatchIn(text)) out += StealFromGraveyard
        Regex("""\bearthbend (\d+|x)""").find(text)?.let { out += Earthbend(n(it.groupValues[1])) }
        if (Regex("""distribute up to that many \+1/\+1 counters""").containsMatchIn(text) && "gained life" in text) out += Counters(LIFE_GAINED, all = false, self = false)
        pumps(text)?.let { out += it }
        Regex("""(each|another target|target) ([a-z]+) (creature )?(you control )?explores|(~|it|that creature) explores|each creature you control explores""").find(text)?.let { m ->
            out += when {
                m.value.startsWith("each creature you control") -> Explore(ExploreWho.ALL)
                m.groupValues[5] == "~" -> Explore(ExploreWho.SELF)
                m.groupValues[5].isNotEmpty() -> Explore(ExploreWho.TRIGGERING)
                m.groupValues[2] == "creature" -> Explore(ExploreWho.ALL)
                else -> Explore(ExploreWho.TRIBE, singular(m.groupValues[2]))
            }
        }
        if (Regex("""put a land card from your hand onto the battlefield""").containsMatchIn(text)) {
            val elseDraw = Regex("""if you don't, draw a card""").containsMatchIn(text)
            if (elseDraw) out.removeAll { it is Draw }
            out += LandFromHand(elseDraw)
        }
        if (Regex("""(cast|play) [^.]*from the top of your library""").containsMatchIn(text)) out += TopOfLibrary
        Regex("""create a token that's a copy of (target|another target|up to one target)? ?(nonlegendary )?creature( you control)?""").find(text)?.let {
            out += CopyCreature(token = true, ownOnly = it.groupValues[3].isNotEmpty())
        }
        if (Regex("""enter as a copy of (any|a) (nonlegendary )?creature""").containsMatchIn(text)) out += CopyCreature(token = false, ownOnly = false)
        if ("extra turn" in text && "take" in text) out += ExtraTurn
        if ("additional combat phase" in text) out += ExtraCombat
        Regex("""if you have (\d+) or more life, you win the game""").find(text)?.let { out += WinGame(WinCondition.LIFE, it.groupValues[1].toInt()) }
        if ("draw a card while your library has no cards in it, you win the game" in text) out += WinGame(WinCondition.EMPTY_LIBRARY)
        if (Regex("""x is greater than or equal to the number of cards in your library, you win the game""").containsMatchIn(text)) out += WinGame(WinCondition.ORACLE)
        return out
    }

    /** Creature type a trigger is limited to: "another Merfolk you control", "of the chosen type" ("chosen"), or null. */
    fun tribeOf(line: String): String? {
        if ("of the chosen type" in line) return "chosen"
        val m = Regex("""^whenever (another|a|one or more) (nontoken )?([a-z]+) (creatures? )?you control""").find(line) ?: return null
        val w = m.groupValues[3]
        return if (w in setOf("creature", "creatures", "nontoken", "other")) null else singular(w)
    }

    /** "+N/+N until end of turn" effects. */
    private fun pumps(text: String): Pump? {
        Regex("""target ([a-z]+ )?creature( you control)? can't be blocked this turn""").find(text)?.let { m ->
            return Pump(0, 0, PumpTarget.ANOTHER, m.groupValues[1].trim().ifBlank { null }?.let { singular(it) }, Kw.UNBLOCKABLE)
        }
        if ("until end of turn" !in text) return null
        Regex("""(up to one )?target creature( you control)? gains ([a-z ,]+?) until end of turn""").find(text)?.takeIf { "gets" !in text }?.let { m ->
            return Pump(0, 0, PumpTarget.ANOTHER, null, Kw.of(m.groupValues[3]))
        }
        val kw = Regex("""gains? ([a-z ,]+?)( and gets| until|\.|$)""").find(text)?.groupValues?.get(1)?.let { Kw.of(it) } ?: 0
        Regex("""gets \+x/\+x until end of turn, where x is its power""").find(text)?.let { return Pump(0, 0, PumpTarget.TRIGGERING, keywords = kw, doublePower = true) }
        Regex("""another target ([a-z]+) (creature )?you control gets \+(\d+)/\+(\d+)""").find(text)?.let { m ->
            return Pump(m.groupValues[3].toInt(), m.groupValues[4].toInt(), PumpTarget.ANOTHER, singular(m.groupValues[1]).takeIf { it != "creature" }, kw)
        }
        Regex("""target creature you control gets \+(\d+)/\+(\d+)""").find(text)?.let { m ->
            return Pump(m.groupValues[1].toInt(), m.groupValues[2].toInt(), PumpTarget.ANOTHER, null, kw)
        }
        Regex("""(other )?([a-z]+ )?creatures you control get \+(\d+)/\+(\d+) (and gain [a-z ,]+ )?until end of turn""").find(text)?.let { m ->
            return Pump(m.groupValues[3].toInt(), m.groupValues[4].toInt(), PumpTarget.ALL, m.groupValues[2].trim().ifBlank { null }?.let { singular(it) }, kw)
        }
        Regex("""(~|it|that creature) gets \+(\d+)/\+(\d+) until end of turn""").find(text)?.let { m ->
            return Pump(m.groupValues[2].toInt(), m.groupValues[3].toInt(), if (m.groupValues[1] == "~") PumpTarget.SELF else PumpTarget.TRIGGERING, null, kw)
        }
        return null
    }

    private fun trigger(line: String, permanent: Boolean): Trigger? = when {
        line.startsWith("at the beginning of combat on your turn") || line.startsWith("at the beginning of each combat") -> Trigger.COMBAT_START
        Regex("""^whenever (a|another) ([a-z]+ )?creature you control explores""").containsMatchIn(line) -> Trigger.EXPLORES
        Regex("""^whenever (another|a) ([a-z]+) (creature )?you control attacks""").containsMatchIn(line) -> Trigger.CREATURE_ATTACKS
        Regex("""^(when|whenever) ~ (enters|or another [a-z ]+ enters)""").containsMatchIn(line) -> Trigger.ETB
        Regex("""^when(ever)? ~ dies""").containsMatchIn(line) -> Trigger.SELF_DIES
        "whenever you attack" in line -> Trigger.ATTACK
        Regex("""^whenever ~ deals combat damage to a player""").containsMatchIn(line) -> Trigger.COMBAT_DAMAGE
        Regex("""^whenever ~ (attacks|deals combat damage)""").containsMatchIn(line) -> Trigger.SELF_ATTACK
        Regex("""^whenever (a|another|one or more) ([a-z]+ )?(creatures? )?you control (of the chosen type )?deals? combat damage to a player""").containsMatchIn(line) -> Trigger.CREATURE_COMBAT_DAMAGE
        Regex("""^whenever (a|another|one or more) [a-z ]*creatures? you control attack""").containsMatchIn(line) -> Trigger.ATTACK
        Regex("""^at the beginning of (your|each) (upkeep|draw step|precombat main phase|first main phase|combat)""").containsMatchIn(line) -> Trigger.UPKEEP
        Regex("""^at the beginning of (your|each) end step""").containsMatchIn(line) -> Trigger.END_STEP
        line.startsWith("landfall") || Regex("""^whenever (a|one or more) lands? (you control )?enters?""").containsMatchIn(line) -> Trigger.LANDFALL
        Regex("""^whenever you cast (a|an|your first|your second)? ?(noncreature|instant or sorcery|instant|sorcery|historic|artifact|enchantment)""").containsMatchIn(line) -> Trigger.CAST_NONCREATURE
        line.startsWith("whenever you cast") -> Trigger.CAST_SPELL
        Regex("""^whenever (one or more |an |another )?(nontoken )?artifacts? (you control )?(enters?|are put)""").containsMatchIn(line) ||
            Regex("""^whenever one or more artifacts enter""").containsMatchIn(line) -> Trigger.ARTIFACT_ETB
        Regex("""^whenever (another |a |one or more )?(other )?(nontoken )?[a-z ]*creatures? (you control )?(of the chosen type )?enters?""").containsMatchIn(line) ||
            Regex("""^whenever (another|a|one or more) (nontoken )?([a-z]+) you control enters?""").containsMatchIn(line) -> Trigger.CREATURE_ETB
        Regex("""^whenever (~ or )?(a|another|one or more) (other )?(nontoken )?[a-z ]*creatures? you control (dies|die|is put into)""").containsMatchIn(line) -> Trigger.OWN_DIES
        Regex("""^whenever (~ or )?(a|another|one or more) (other )?(nontoken )?creatures? (dies|die)""").containsMatchIn(line) -> Trigger.DIES
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
        for (raw in text.split('\n').map { it.trim() }.filter { it.isNotBlank() }) {
            val isMode = raw.startsWith("•")
            val line = raw.removePrefix("•").trim()
            if (modes != null && line.startsWith("choose ")) { understood += "modal: choose $modes"; continue }
            if (keywordLine.containsMatchIn(line) && !line.contains(':') ||
                line.startsWith("equip") || line.startsWith("crew") || line.startsWith("enchant ")) {
                understood += "keyword: $line"; continue
            }
            // Saga chapters.
            val ch = chapter.find(line)
            if (ch != null) {
                val eff = effects(ch.groupValues[2])
                if (eff.isNotEmpty() || roleLine(ch.groupValues[2])) {
                    ch.groupValues[1].split(", ").forEach { r -> chapters.getOrPut(romans.getValue(r)) { mutableListOf() } += eff }
                    understood += "chapter ${ch.groupValues[1].uppercase()}: " + (eff.joinToString("; ") { it.describe() }.ifBlank { ch.groupValues[2].take(50) })
                } else missed += raw
                continue
            }
            // Planeswalker loyalty abilities: "+1: …", "-2: …", "0: …".
            val loy = Regex("""^([+-]?)(\d+|x): (.+)$""").find(line)
            if (loy != null) {
                val eff = effects(loy.groupValues[3])
                val delta = (loy.groupValues[2].toIntOrNull() ?: 3) * (if (loy.groupValues[1] == "-") -1 else 1)
                if (eff.isNotEmpty() || roleLine(loy.groupValues[3])) {
                    loyalty += Loyalty(delta, eff)
                    understood += Loyalty(delta, eff).describe()
                } else missed += raw
                continue
            }
            // Activated ability: "cost: effect" (after any ability word: "grand summon — {t}: …").
            val act = line.replace(Regex("""^[a-z' ]+ — (?=\{)"""), "")
            val colon = act.indexOf(':')
            if (colon in 1..80 && Regex("""^(\{[^}]+\}|,|\s|sacrifice ~|sacrifice (a|another) [a-z]+|tap (an|two|three|four|five|ten|x|\d+) untapped [a-z ]+|pay \d+ life|discard a card|remove [a-z ]+ counters? from ~|exert ~)+$""")
                    .matches(act.substring(0, colon))) {
                val costText = act.substring(0, colon)
                val effectText = act.substring(colon + 1)
                if (Regex("""\badd \{|\badd one mana|\badd (two|three|x) mana|\badd an amount""").containsMatchIn(effectText)) { understood += "mana ability"; continue }
                val eff = effects(effectText)
                if (eff.isNotEmpty()) {
                    val sac = Regex("sacrifice (a|another) ([a-z]+)").find(costText)?.groupValues?.get(2)
                    val tapN = Regex("tap (an|two|three|four|five|ten|x|\\d+) untapped ([a-z ]+?)( you control)?$").find(costText.substringAfterLast(", "))
                    val a = Activated(
                        ManaCost.parse(costText.replace("{t}", "").replace("{q}", ""), 0),
                        tap = "{t}" in costText, sacrificeSelf = "sacrifice ~" in costText, effects = eff,
                        sacrificeCreature = sac != null,
                        sacrificeTribe = sac?.let { singular(it) }?.takeIf { it != "creature" && it != "token" },
                        tapCreatures = tapN?.let { n(it.groupValues[1]).let { v -> if (v == XN) 3 else v } } ?: 0,
                        tapTribe = tapN?.groupValues?.get(2)?.split(' ')?.last()?.let { singular(it) }?.takeIf { it != "creature" },
                    )
                    activated += a
                    understood += a.describe()
                } else if (roleLine(effectText)) understood += "activated: ${effectText.trim()}" else missed += raw
                continue
            }
            if (isMode && modes != null) {
                val eff = effects(line)
                when {
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
            val body = line.replace(Regex("""^[a-z ]+ — """), "")
            val trig = trigger(body, permanent)
            val eff = effects(body)
            when {
                trig != null && eff.isNotEmpty() -> {
                    val optional = Regex("""you may pay ((?:\{[^}]+\})+)\. if you do""").find(body)?.let { ManaCost.parse(it.groupValues[1]) }
                    val tribe = if (trig == Trigger.CREATURE_ATTACKS || trig == Trigger.CREATURE_ETB || trig == Trigger.CREATURE_COMBAT_DAMAGE) tribeOf(body) else null
                    val a = Ability(trig, eff, oncePerTurn = "only once each turn" in line, fromCommandZone = eminence, optionalCost = optional, tribe = tribe)
                    // "Whenever a creature of the chosen type enters or attacks": both.
                    if (trig == Trigger.CREATURE_ETB && Regex("""enters or attacks""").containsMatchIn(body)) abilities += a.copy(trigger = Trigger.CREATURE_ATTACKS)
                    abilities += a
                    understood += a.describe()
                    if (roleLine(line)) understood += "role: ${line.take(60)}"
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
        val chapterList = if (chapters.isEmpty()) emptyList() else (1..chapters.lastKey()).map { chapters[it].orEmpty() }
        return Parsed(abilities, activated, loyalty, chapterList, Coverage(understood, missed))
    }
}
