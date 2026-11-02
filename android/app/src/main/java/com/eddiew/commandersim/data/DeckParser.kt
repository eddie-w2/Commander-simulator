package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.DeckCard

/**
 * Parses pasted deck lists. Handles the common export formats:
 *  - Plain / MTGO:   "1 Sol Ring"
 *  - Moxfield:       "1 Sol Ring (CMR) 472 *F*"
 *  - Archidekt:      "1x Sol Ring (cmr) 472 [Ramp]", commander tagged "[Commander{top}]"
 *  - Arena:          "Commander" / "Deck" section headers
 *  - Deckstats:      "1 Atraxa, Praetors' Voice # !Commander" or "*CMDR*"
 *  - "// Commander", "COMMANDER:", "Sideboard:", "Maybeboard" headers
 */
object DeckParser {

    data class Result(
        val commanders: List<DeckCard>,
        val cards: List<DeckCard>,
        val ignored: List<String>,
        val warnings: List<String>,
        /** True when the commander was inferred from the list's layout rather than marked. */
        val commanderInferred: Boolean = false,
    ) {
        val total: Int get() = commanders.sumOf { it.quantity } + cards.sumOf { it.quantity }
    }

    private enum class Section { MAIN, COMMANDER, SIDEBOARD, SKIP }

    private val headerRegex = Regex(
        """^(?://\s*)?(commanders?|command zone|deck|main\s*deck|mainboard|main|sideboard|maybeboard|maybe|considering|companion|tokens?|about|name)\s*:?\s*(?:\(\d+\))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val categoryRegex = Regex("""^([A-Za-z][A-Za-z /&'-]*?)\s*\(\d+\)\s*:?$""")
    private val skipCategories = setOf("sideboard", "maybeboard", "maybe", "considering", "tokens", "token", "companion")
    private val countRegex =Regex("""^(\d+)\s*[xX]?\s+(.+)$""")
    private val setNumberRegex = Regex("""\s+\(([A-Za-z0-9]{2,6})\)(?:\s+([A-Za-z0-9\-★]+))?\s*$""")
    private val bracketTagRegex = Regex("""\[([^\]]*)\]""")
    private val caretTagRegex = Regex("""\^[^^]*\^""")
    private val foilRegex = Regex("""\s+\*[A-Za-z]{1,6}\*""")
    private val commanderMarker = Regex("""\*CMDR\*|#\s*!?commander\b""", RegexOption.IGNORE_CASE)
    private val hashTagRegex = Regex("""\s+#\S.*$""")

    val basicLands = setOf(
        "plains", "island", "swamp", "mountain", "forest", "wastes",
        "snow-covered plains", "snow-covered island", "snow-covered swamp",
        "snow-covered mountain", "snow-covered forest", "snow-covered wastes",
    )

    /** Cards whose text allows any number (or a fixed number above one) in a deck. */
    val multiCopyAllowed = setOf(
        "relentless rats", "shadowborn apostle", "rat colony", "persistent petitioners",
        "dragon's approach", "slime against humanity", "hare apparent", "templar knight",
        "seven dwarves", "nazgûl", "nazgul", "cid, timeless artificer",
    )

    fun parse(text: String): Result {
        var section = Section.MAIN
        val commanders = LinkedHashMap<String, DeckCard>()
        val cards = LinkedHashMap<String, DeckCard>()
        val sideboard = mutableListOf<DeckCard>()
        val ignored = mutableListOf<String>()
        // Main-deck cards split by blank lines, used to spot a commander listed on its own.
        val groups = mutableListOf(mutableListOf<DeckCard>())

        fun add(target: LinkedHashMap<String, DeckCard>, card: DeckCard) {
            val key = card.name.lowercase()
            val existing = target[key]
            target[key] = existing?.copy(quantity = existing.quantity + card.quantity) ?: card
        }

        for (raw in text.lines()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty()) {
                if (groups.last().isNotEmpty()) groups += mutableListOf<DeckCard>()
                continue
            }

            val header = headerRegex.matchEntire(line)
            if (header != null) {
                val h = header.groupValues[1].lowercase()
                section = when {
                    h.startsWith("command") -> Section.COMMANDER
                    h.startsWith("deck") || h.startsWith("main") -> Section.MAIN
                    h.startsWith("sideboard") -> Section.SIDEBOARD
                    else -> Section.SKIP
                }
                if (groups.last().isNotEmpty()) groups += mutableListOf<DeckCard>()
                continue
            }
            // Category headers such as "Creatures (30)" or "Sideboard (5):"
            val category = categoryRegex.matchEntire(line)
            if (category != null) {
                val c = category.groupValues[1].trim().lowercase()
                section = when {
                    c.startsWith("command") -> Section.COMMANDER
                    c == "sideboard" -> Section.SIDEBOARD
                    c in skipCategories -> Section.SKIP
                    else -> Section.MAIN
                }
                continue
            }
            if (line.startsWith("//") || line.startsWith("#")) continue
            if (section == Section.SKIP) {
                ignored += line
                continue
            }

            val parsed = parseCardLine(line)
            if (parsed == null) {
                ignored += line
                continue
            }
            val (card, flaggedCommander) = parsed
            when {
                section == Section.COMMANDER || flaggedCommander -> add(commanders, card)
                section == Section.SIDEBOARD -> sideboard += card
                else -> {
                    add(cards, card)
                    groups.last() += card
                }
            }
        }

        var inferred = false
        if (commanders.isEmpty()) {
            val mainTotal = cards.values.sumOf { it.quantity }
            val nonEmpty = groups.filter { it.isNotEmpty() }
            fun isCommanderGroup(g: List<DeckCard>) = g.sumOf { it.quantity } in 1..2 && g.all { it.quantity == 1 } &&
                g.none { it.name.lowercase() in basicLands }
            val candidate = when {
                // MTGO-style exports put the commander in the sideboard.
                sideboard.sumOf { it.quantity } in 1..2 && mainTotal + sideboard.sumOf { it.quantity } == 100 -> sideboard
                nonEmpty.size >= 2 && mainTotal == 100 && isCommanderGroup(nonEmpty.first()) -> nonEmpty.first()
                nonEmpty.size >= 2 && mainTotal == 100 && isCommanderGroup(nonEmpty.last()) -> nonEmpty.last()
                else -> null
            }
            if (candidate != null) {
                for (c in candidate) {
                    cards.remove(c.name.lowercase())
                    add(commanders, c)
                }
                inferred = true
            }
        }
        if (!inferred) sideboard.forEach { ignored += "${it.quantity} ${it.name}" }

        val result = Result(commanders.values.toList(), cards.values.toList(), ignored, emptyList(), inferred)
        return result.copy(warnings = validate(result))
    }

    /**
     * Picks the commander(s) using card data: a legendary creature (or a card that says it can be your commander)
     * whose colour identity covers every other card. Two partners are allowed for 98+2 lists.
     * Returns null when the answer isn't clear.
     */
    fun detectCommander(cards: List<DeckCard>, info: Map<String, CardFacts>): List<DeckCard>? {
        val facts = cards.associateWith { info[it.name.lowercase()] ?: info[it.name.substringBefore(" // ").lowercase()] }
        if (facts.values.count { it != null } < cards.size * 0.8) return null
        val eligible = cards.filter { facts[it]?.canBeCommander == true && it.quantity == 1 }
        if (eligible.isEmpty()) return null
        fun covers(identity: String, others: List<DeckCard>) =
            others.all { o -> (facts[o]?.colorIdentity ?: "").all { it in identity } }

        val singles = eligible.filter { e -> covers(facts[e]!!.colorIdentity, cards - e) }
        // Partner pairs: both have a partner-style ability and together cover the rest of the deck.
        val partners = eligible.filter { facts[it]!!.partner }
        val pairs = mutableListOf<List<DeckCard>>()
        for (i in partners.indices) for (j in i + 1 until partners.size) {
            val pair = listOf(partners[i], partners[j])
            val identity = facts[partners[i]]!!.colorIdentity + facts[partners[j]]!!.colorIdentity
            if (covers(identity, cards - pair.toSet())) pairs += pair
        }
        return when {
            singles.size == 1 && pairs.isEmpty() -> singles
            singles.isEmpty() && pairs.size == 1 -> pairs[0]
            else -> null // ambiguous: let the user pick
        }
    }

    /** Card facts needed for commander detection. */
    data class CardFacts(val colorIdentity: String, val canBeCommander: Boolean, val partner: Boolean)

    /** Returns the card and whether the line itself marks it as a commander. */
    internal fun parseCardLine(line: String): Pair<DeckCard, Boolean>? {
        var rest = line
        var isCommander = commanderMarker.containsMatchIn(rest)
        rest = commanderMarker.replace(rest, "")

        // Archidekt category tags like [Commander{top}] or [Ramp,Draw]
        for (m in bracketTagRegex.findAll(rest)) {
            if (m.groupValues[1].contains("commander", ignoreCase = true)) isCommander = true
        }
        rest = bracketTagRegex.replace(rest, "")
        rest = caretTagRegex.replace(rest, "")
        rest = foilRegex.replace(rest, "")
        rest = hashTagRegex.replace(rest, "")
        rest = rest.trim()

        var quantity = 1
        countRegex.matchEntire(rest)?.let {
            quantity = it.groupValues[1].toInt()
            rest = it.groupValues[2].trim()
        }
        if (quantity <= 0) return null

        var setCode: String? = null
        var number: String? = null
        setNumberRegex.find(rest)?.let {
            setCode = it.groupValues[1].uppercase()
            number = it.groupValues[2].ifEmpty { null }
            rest = rest.substring(0, it.range.first).trim()
        }

        // Normalise split/double-faced separators: "Fire/Ice" or "Fire / Ice" -> "Fire // Ice"
        rest = rest.replace(Regex("""\s*/{1,2}\s*"""), " // ")
        if (rest.isBlank() || rest.all { it.isDigit() }) return null
        return DeckCard(name = rest, quantity = quantity, setCode = setCode, collectorNumber = number) to isCommander
    }

    fun validate(result: Result): List<String> {
        val warnings = mutableListOf<String>()
        if (result.commanders.isEmpty()) {
            warnings += "No commander found. Pick one from the list."
        } else if (result.commanders.size > 2) {
            warnings += "${result.commanders.size} commanders marked. Commander allows 1, or 2 with Partner/Background."
        }
        if (result.total != 100) warnings += "Deck has ${result.total} cards (Commander decks have exactly 100)."
        val dupes = result.cards.filter {
            it.quantity > 1 && it.name.lowercase() !in basicLands && it.name.lowercase() !in multiCopyAllowed
        }
        if (dupes.isNotEmpty()) {
            warnings += "More than one copy of: " + dupes.joinToString(", ") { "${it.name} ×${it.quantity}" }
        }
        return warnings
    }

    /** Guess a deck name from the commander when the user didn't give one. */
    fun suggestName(result: Result): String =
        result.commanders.firstOrNull()?.name?.substringBefore(",")?.substringBefore(" // ")?.trim()
            ?: "Untitled deck"
}
