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
}

enum class TokenKind { CREATURE, TREASURE, FOOD, CLUE }

/** Keyword bits shared by cards and tokens. */
object Kw {
    const val FLYING = 1; const val TRAMPLE = 2; const val HASTE = 4; const val DEATHTOUCH = 8; const val LIFELINK = 16
    const val REACH = 32; const val FIRST_STRIKE = 64; const val DOUBLE_STRIKE = 128; const val VIGILANCE = 256; const val MENACE = 512
    const val INDESTRUCTIBLE = 1024; const val HEXPROOF = 2048; const val WARD = 4096; const val DEFENDER = 8192; const val CANT_BLOCK = 16384
    const val UNBLOCKABLE = 32768; const val INFECT = 65536

    private val words = listOf(
        "flying" to FLYING, "trample" to TRAMPLE, "haste" to HASTE, "deathtouch" to DEATHTOUCH, "lifelink" to LIFELINK,
        "reach" to REACH, "first strike" to FIRST_STRIKE, "double strike" to DOUBLE_STRIKE, "vigilance" to VIGILANCE,
        "menace" to MENACE, "indestructible" to INDESTRUCTIBLE, "hexproof" to HEXPROOF, "shroud" to HEXPROOF, "ward" to WARD,
        "defender" to DEFENDER, "infect" to INFECT,
    )

    /** Bits for keywords named in [text] (a "with …" clause or Scryfall's keyword list). */
    fun of(text: String): Int = words.fold(0) { m, (w, bit) -> if (Regex("\\b$w\\b").containsMatchIn(text)) m or bit else m }

    fun names(bits: Int): List<String> = words.filter { bits and it.second != 0 }.map { it.first }.distinct()
}

/** "X" in an effect: the X paid for the spell (or 2 when there's no X to pay). */
const val XN = -1

enum class ScaleKind(val label: String) {
    CREATURES("creature you control"), ARTIFACTS("artifact you control"), LANDS("land you control"),
    LANDS_OF_COLOR("land of that type"), TRIBE("creature of that type"), OPPONENTS("opponent"), DEVOTION("devotion"),
    HAND("card in hand"), ENCHANTMENTS("enchantment you control"),
}

/** "For each …" / "equal to …" multipliers, counted when the effect happens. */
data class Scale(val kind: ScaleKind, val color: Int = 0, val tribe: String? = null) {
    fun describe() = " for each ${tribe ?: kind.label}"
}

sealed interface Effect {
    fun describe(): String
}

private fun amount(n: Int) = if (n == XN) "X" else "$n"

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

enum class MillTarget { SELF, OPPONENT, EACH_OPPONENT }
data class Mill(val n: Int, val target: MillTarget) : Effect {
    override fun describe() = when (target) { MillTarget.SELF -> "mill yourself"; MillTarget.OPPONENT -> "mill an opponent"; else -> "mill each opponent" } + " ${amount(n)}"
}
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

data class Ability(val trigger: Trigger, val effects: List<Effect>, val oncePerTurn: Boolean = false) {
    fun describe() = "${trigger.label}${if (oncePerTurn) " (once a turn)" else ""}: " + effects.joinToString("; ") { it.describe() }
}

data class Activated(
    val cost: ManaCost, val tap: Boolean, val sacrificeSelf: Boolean, val effects: List<Effect>,
    /** Cost includes sacrificing another creature (paid with a small token). */
    val sacrificeCreature: Boolean = false,
) {
    fun describe() = "activate (${cost.total} mana${if (tap) ", tap" else ""}${if (sacrificeSelf) ", sacrifice it" else ""}" +
        "${if (sacrificeCreature) ", sacrifice a creature" else ""}): " + effects.joinToString("; ") { it.describe() }
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
        "seven" to 7, "eight" to 8, "ten" to 10, "x" to XN, "that many" to 2)
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

    fun effects(text: String): List<Effect> {
        val out = mutableListOf<Effect>()
        val sc = scale(text)
        fun scaled(base: Int) = if (sc != null && (base == 1 || base == XN)) sc else null
        // Tokens: one "create …" clause at a time.
        Regex("""create ([^.;]*?tokens?)([^.;]*)""").findAll(text).forEach { m ->
            val clause = m.groupValues[1]
            val rest = m.groupValues[2]
            if ("copy" in clause || "copies" in clause) return@forEach
            val count = n(Regex("""^(a|an|one|two|three|four|five|six|x|that many|\d+)\b""").find(clause)?.value)
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
        Regex("""put (a|an|one|two|three|x|\d+) \+1/\+1 counters? on (each (other )?creature you control|each creature|target creature( you control)?|~|it|up to one target creature|another target creature)""")
            .find(text)?.let {
                val where = it.groupValues[2]
                out += Counters(n(it.groupValues[1]), all = where.startsWith("each"), self = where == "~" || where == "it", scale = scaled(n(it.groupValues[1])))
            }
        Regex("""~ enters with (a|one|two|three|four|x|\d+) \+1/\+1 counters?""").find(text)?.let {
            out += Counters(n(it.groupValues[1]), all = false, self = true, scale = scaled(n(it.groupValues[1])))
        }
        Regex("""(each opponent|target player|target opponent|you) mills? (a|one|two|three|four|five|ten|x|\d+) cards?""").find(text)?.let {
            out += Mill(n(it.groupValues[2]), when (it.groupValues[1]) { "you" -> MillTarget.SELF; "each opponent" -> MillTarget.EACH_OPPONENT; else -> MillTarget.OPPONENT })
        } ?: Regex("""^mill (a|one|two|three|four|five|x|\d+) cards?""").find(text)?.let { out += Mill(n(it.groupValues[1]), MillTarget.SELF) }
        Regex("""(each opponent|target player|target opponent) gets? (a|one|two|three|x|\d+) poison counters?""").find(text)?.let {
            out += Poison(n(it.groupValues[2]), each = it.groupValues[1] == "each opponent")
        }
        if (Regex("""\bproliferate\b""").containsMatchIn(text)) out += Proliferate
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

    private fun trigger(line: String, permanent: Boolean): Trigger? = when {
        Regex("""^(when|whenever) ~ (enters|or another [a-z ]+ enters)""").containsMatchIn(line) -> Trigger.ETB
        Regex("""^when(ever)? ~ dies""").containsMatchIn(line) -> Trigger.SELF_DIES
        "whenever you attack" in line -> Trigger.ATTACK
        Regex("""^whenever ~ deals combat damage to a player""").containsMatchIn(line) -> Trigger.COMBAT_DAMAGE
        Regex("""^whenever ~ (attacks|deals combat damage)""").containsMatchIn(line) -> Trigger.SELF_ATTACK
        Regex("""^whenever (a|another|one or more) [a-z ]*creatures? you control (attack|deals combat damage)""").containsMatchIn(line) -> Trigger.ATTACK
        Regex("""^at the beginning of (your|each) (upkeep|draw step|precombat main phase|first main phase|combat)""").containsMatchIn(line) -> Trigger.UPKEEP
        Regex("""^at the beginning of (your|each) end step""").containsMatchIn(line) -> Trigger.END_STEP
        line.startsWith("landfall") || Regex("""^whenever (a|one or more) lands? (you control )?enters?""").containsMatchIn(line) -> Trigger.LANDFALL
        Regex("""^whenever you cast (a|an|your first|your second)? ?(noncreature|instant or sorcery|instant|sorcery|historic|artifact|enchantment)""").containsMatchIn(line) -> Trigger.CAST_NONCREATURE
        line.startsWith("whenever you cast") -> Trigger.CAST_SPELL
        Regex("""^whenever (one or more |an |another )?(nontoken )?artifacts? (you control )?(enters?|are put)""").containsMatchIn(line) ||
            Regex("""^whenever one or more artifacts enter""").containsMatchIn(line) -> Trigger.ARTIFACT_ETB
        Regex("""^whenever (another |a |one or more )?(other )?(nontoken )?[a-z ]*creatures? (you control )?enters?""").containsMatchIn(line) -> Trigger.CREATURE_ETB
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
            // Activated ability: "cost: effect".
            val colon = line.indexOf(':')
            if (colon in 1..80 && Regex("""^(\{[^}]+\}|,|\s|sacrifice ~|sacrifice (a|another) (creature|token)|tap an untapped [a-z ]+|pay \d+ life|discard a card|remove [a-z ]+ counters? from ~)+$""")
                    .matches(line.substring(0, colon))) {
                val costText = line.substring(0, colon)
                val effectText = line.substring(colon + 1)
                if (Regex("""\badd \{|\badd one mana|\badd (two|three|x) mana|\badd an amount""").containsMatchIn(effectText)) { understood += "mana ability"; continue }
                val eff = effects(effectText)
                if (eff.isNotEmpty()) {
                    val a = Activated(
                        ManaCost.parse(costText.replace("{t}", "").replace("{q}", ""), 0),
                        tap = "{t}" in costText, sacrificeSelf = "sacrifice ~" in costText, effects = eff,
                        sacrificeCreature = Regex("sacrifice (a|another) (creature|token)").containsMatchIn(costText),
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
            val trig = trigger(line, permanent)
            val eff = effects(line)
            when {
                trig != null && eff.isNotEmpty() -> {
                    val a = Ability(trig, eff, oncePerTurn = "only once each turn" in line)
                    abilities += a
                    understood += a.describe()
                    if (roleLine(line)) understood += "role: ${line.take(60)}"
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
