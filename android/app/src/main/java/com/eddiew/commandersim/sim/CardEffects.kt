package com.eddiew.commandersim.sim

/*
 * Rules-text reading for the quick model. Each card's text is read once, when a run starts, into triggered and
 * activated abilities made of a few effect types. The game loop then only counts and compares numbers, which keeps
 * the model fast. Text the reader doesn't understand is reported (see [Coverage]) rather than guessed at.
 */

/** When a triggered ability fires. */
enum class Trigger(val label: String) {
    CAST("on cast"),
    ETB("enters"),
    SELF_DIES("it dies"),
    ATTACK("you attack"),
    SELF_ATTACK("it attacks"),
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
    const val UNBLOCKABLE = 32768

    private val words = listOf(
        "flying" to FLYING, "trample" to TRAMPLE, "haste" to HASTE, "deathtouch" to DEATHTOUCH, "lifelink" to LIFELINK,
        "reach" to REACH, "first strike" to FIRST_STRIKE, "double strike" to DOUBLE_STRIKE, "vigilance" to VIGILANCE,
        "menace" to MENACE, "indestructible" to INDESTRUCTIBLE, "hexproof" to HEXPROOF, "shroud" to HEXPROOF, "ward" to WARD,
        "defender" to DEFENDER,
    )

    /** Bits for keywords named in [text] (a "with …" clause or Scryfall's keyword list). */
    fun of(text: String): Int = words.fold(0) { m, (w, bit) -> if (Regex("\\b$w\\b").containsMatchIn(text)) m or bit else m }

    fun names(bits: Int): List<String> = words.filter { bits and it.second != 0 }.map { it.first }.distinct()
}

sealed interface Effect {
    fun describe(): String
}

data class MakeTokens(
    val count: Int, val kind: TokenKind, val name: String = "", val power: Int = 0, val toughness: Int = 0, val keywords: Int = 0,
) : Effect {
    override fun describe(): String = when (kind) {
        TokenKind.CREATURE -> "create $count $power/$toughness $name token${if (count > 1) "s" else ""}" +
            Kw.names(keywords).takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
        else -> "create $count ${kind.name.lowercase()}${if (count > 1) "s" else ""}"
    }
}

data class Draw(val n: Int) : Effect { override fun describe() = "draw $n" }
data class LoseLife(val n: Int, val each: Boolean) : Effect { override fun describe() = (if (each) "each opponent" else "an opponent") + " loses $n" }
data class Damage(val n: Int, val each: Boolean) : Effect { override fun describe() = "$n damage to " + if (each) "each opponent" else "an opponent" }
data class GainLife(val n: Int) : Effect { override fun describe() = "gain $n life" }
data class Counters(val n: Int, val all: Boolean, val self: Boolean) : Effect {
    override fun describe() = "+1/+1 counter ×$n on " + when { all -> "each creature you control"; self -> "itself"; else -> "a creature" }
}

data class Ability(val trigger: Trigger, val effects: List<Effect>, val oncePerTurn: Boolean = false) {
    fun describe() = "${trigger.label}${if (oncePerTurn) " (once a turn)" else ""}: " + effects.joinToString("; ") { it.describe() }
}

data class Activated(val cost: ManaCost, val tap: Boolean, val sacrificeSelf: Boolean, val effects: List<Effect>) {
    fun describe() = "activate (${cost.total} mana${if (tap) ", tap" else ""}${if (sacrificeSelf) ", sacrifice" else ""}): " +
        effects.joinToString("; ") { it.describe() }
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
        "seven" to 7, "x" to 2, "that many" to 2)
    private fun n(word: String?): Int = word?.trim()?.let { num[it] ?: it.toIntOrNull() } ?: 1

    data class Parsed(val abilities: List<Ability>, val activated: List<Activated>, val coverage: Coverage)

    /** Normalises rules text: lowercase, the card's name (and short name) as "~", no reminder text. */
    fun normalise(name: String, oracle: String): String {
        var t = oracle.lowercase().replace(Regex("""\([^)]*\)"""), "")
        val full = name.substringBefore(" // ").lowercase()
        val short = full.substringBefore(",").trim()
        t = t.replace(full, "~")
        if (short.length >= 4) t = t.replace(Regex("\\b${Regex.escape(short)}\\b"), "~")
        return t.replace("this creature", "~").replace("enters the battlefield", "enters")
    }

    fun effects(text: String): List<Effect> {
        val out = mutableListOf<Effect>()
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
            if (kind != TokenKind.CREATURE) { out += MakeTokens(count, kind); return@forEach }
            val pt = Regex("""(\d+|x)/(\d+|x)""").find(clause)
            val p = pt?.groupValues?.get(1)?.let { if (it == "x") 2 else it.toInt() } ?: 1
            val tough = pt?.groupValues?.get(2)?.let { if (it == "x") 2 else it.toInt() } ?: 1
            val type = Regex("""(?:\d+|x)/(?:\d+|x) ([a-z ]+?) (?:artifact )?(?:enchantment )?creature""").find(clause)?.groupValues?.get(1)
                ?.split(' ')?.filterNot { it in setOf("white", "blue", "black", "red", "green", "colorless", "and", "legendary", "snow") }
                ?.joinToString(" ")?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "Creature"
            val with = Regex("""with ([a-z ,]+)""").find(clause + rest)?.groupValues?.get(1).orEmpty()
            out += MakeTokens(count, kind, type, p, tough, Kw.of(with))
        }
        Regex("""draws? (a|an|one|two|three|four|five|x|\d+) cards?""").find(text)?.let {
            if (!Regex("""(opponent|each player) draws""").containsMatchIn(text)) out += Draw(n(it.groupValues[1]))
        }
        Regex("""each opponent loses (\d+|x|one|two|three) life""").find(text)?.let { out += LoseLife(n(it.groupValues[1]), each = true) }
            ?: Regex("""target (player|opponent) loses (\d+|x|one|two|three) life""").find(text)?.let { out += LoseLife(n(it.groupValues[2]), each = false) }
        Regex("""deals? (\d+|x) damage to (each opponent|each player|target player|target opponent|any target|target player or planeswalker)""").find(text)?.let {
            out += Damage(n(it.groupValues[1]), each = it.groupValues[2].startsWith("each"))
        }
        Regex("""you gain (\d+|x|one|two|three) life""").find(text)?.let { out += GainLife(n(it.groupValues[1])) }
        Regex("""put (a|an|one|two|three|x|\d+) \+1/\+1 counters? on (each (other )?creature you control|each creature|target creature( you control)?|~|it|up to one target creature|another target creature)""")
            .find(text)?.let {
                val where = it.groupValues[2]
                out += Counters(n(it.groupValues[1]), all = where.startsWith("each"), self = where == "~" || where == "it")
            }
        return out
    }

    private fun trigger(line: String, permanent: Boolean): Trigger? = when {
        Regex("""^(when|whenever) ~ (enters|or another [a-z ]+ enters)""").containsMatchIn(line) -> Trigger.ETB
        Regex("""^when(ever)? ~ dies""").containsMatchIn(line) -> Trigger.SELF_DIES
        "whenever you attack" in line -> Trigger.ATTACK
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
        else -> null
    }

    private val keywordLine = Regex(
        """^(flying|first strike|double strike|deathtouch|haste|hexproof|indestructible|lifelink|menace|reach|trample|vigilance|""" +
            """defender|shroud|flash|prowess|ward|protection from|partner|equip|enchant|changeling|convoke|cascade|""" +
            """affinity|delve|storm|crew|kicker|flashback|cycling|landcycling|ninjutsu|choose a background|friends forever)[^.]*$""",
    )

    /**
     * Reads [oracle]. [roleLines] says whether a line is already handled by the role-based effects (removal, wipes,
     * counters, ramp, tutors, anthems…) so it counts as understood.
     */
    fun parse(name: String, oracle: String?, permanent: Boolean, roleLine: (String) -> Boolean): Parsed {
        if (oracle.isNullOrBlank()) return Parsed(emptyList(), emptyList(), Coverage(emptyList(), emptyList()))
        val abilities = mutableListOf<Ability>()
        val activated = mutableListOf<Activated>()
        val understood = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val face = oracle.substringBefore("\n//").substringBefore(" // ")
        for (raw in normalise(name, face).split('\n').map { it.trim() }.filter { it.isNotBlank() }) {
            val line = raw.removePrefix("• ").trim()
            if (keywordLine.containsMatchIn(line) && !line.contains(':') ||
                line.startsWith("equip") || line.startsWith("crew") || line.startsWith("enchant ")) {
                understood += "keyword: $line"; continue
            }
            // Activated ability: "cost: effect".
            val colon = line.indexOf(':')
            if (colon in 1..60 && Regex("""^(\{[^}]+\}|,|\s|sacrifice ~|tap an untapped [a-z ]+|pay \d+ life|discard a card)+$""").matches(line.substring(0, colon))) {
                val costText = line.substring(0, colon)
                val effectText = line.substring(colon + 1)
                if (Regex("""\badd \{|\badd one mana|\badd (two|three) mana""").containsMatchIn(effectText)) { understood += "mana ability"; continue }
                val eff = effects(effectText)
                if (eff.isNotEmpty()) {
                    val a = Activated(
                        ManaCost.parse(costText.replace("{t}", "").replace("{q}", ""), 0),
                        tap = "{t}" in costText, sacrificeSelf = "sacrifice ~" in costText, effects = eff,
                    )
                    activated += a
                    understood += a.describe()
                } else if (roleLine(effectText)) understood += "activated: ${effectText.trim()}" else missed += raw
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
                roleLine(line) -> understood += "role: ${line.take(70)}"
                else -> missed += raw
            }
        }
        return Parsed(abilities, activated, Coverage(understood, missed))
    }
}
