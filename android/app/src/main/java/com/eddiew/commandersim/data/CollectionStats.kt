package com.eddiew.commandersim.data

import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckCard

/** Analytics across the decks the user owns: shared cards, overlap and colour breakdowns. */
object CollectionStats {

    /** A card that appears in more than one owned deck. */
    data class SharedCard(val name: String, val type: CardType, val colors: String?, val deckIds: List<String>) {
        val count: Int get() = deckIds.size
    }

    /** Colour counts: W, U, B, R, G, colourless (C). Multicolour cards count once per colour. */
    data class ColorCounts(val counts: Map<Char, Int>, val mono: Int, val multi: Int, val colorless: Int, val unknown: Int) {
        operator fun get(c: Char): Int = counts[c] ?: 0
        val total: Int get() = mono + multi + colorless
        operator fun plus(o: ColorCounts) = ColorCounts(
            (counts.keys + o.counts.keys).associateWith { this[it] + o[it] },
            mono + o.mono, multi + o.multi, colorless + o.colorless, unknown + o.unknown,
        )

        companion object {
            val EMPTY = ColorCounts(emptyMap(), 0, 0, 0, 0)
            const val ORDER = "WUBRGC"
        }
    }

    private fun allCards(d: Deck): List<DeckCard> = d.commanders + d.cards

    private fun include(c: DeckCard, basics: Boolean, lands: Boolean): Boolean {
        if (!basics && c.name.lowercase() in DeckParser.basicLands) return false
        if (!lands && c.type == CardType.LAND) return false
        return true
    }

    /** Cards in two or more of [decks], most-shared first. */
    fun sharedCards(decks: List<Deck>, includeBasics: Boolean = false, includeLands: Boolean = true): List<SharedCard> {
        val byName = LinkedHashMap<String, MutableList<String>>()
        val sample = HashMap<String, DeckCard>()
        for (d in decks) {
            for (c in allCards(d).filter { include(it, includeBasics, includeLands) }) {
                val key = c.name.lowercase()
                val list = byName.getOrPut(key) { mutableListOf() }
                if (d.id !in list) list += d.id
                sample.putIfAbsent(key, c)
            }
        }
        return byName.filter { it.value.size >= 2 }.map { (k, ids) ->
            val c = sample.getValue(k)
            SharedCard(c.name, c.type, c.colors, ids)
        }.sortedWith(compareByDescending<SharedCard> { it.count }.thenBy { it.name })
    }

    /** Number of distinct non-basic cards each pair of decks has in common. */
    fun overlap(decks: List<Deck>, includeBasics: Boolean = false): List<List<Int>> {
        val sets = decks.map { d -> allCards(d).filter { include(it, includeBasics, true) }.map { it.name.lowercase() }.toSet() }
        return sets.map { a -> sets.map { b -> (a intersect b).size } }
    }

    /** Colour breakdown of one deck, counting card quantities. */
    fun colors(deck: Deck, includeLands: Boolean = false): ColorCounts = colorsOf(allCards(deck), includeLands)

    fun colorsOf(cards: List<DeckCard>, includeLands: Boolean = false): ColorCounts {
        val counts = HashMap<Char, Int>()
        var mono = 0; var multi = 0; var colorless = 0; var unknown = 0
        for (c in cards) {
            if (!includeLands && c.type == CardType.LAND) continue
            val colors = c.colors
            if (colors == null) { unknown += c.quantity; continue }
            val set = colors.filter { it in "WUBRG" }.toSet()
            when {
                set.isEmpty() -> { colorless += c.quantity; counts.merge('C', c.quantity, Int::plus) }
                set.size == 1 -> mono += c.quantity
                else -> multi += c.quantity
            }
            set.forEach { counts.merge(it, c.quantity, Int::plus) }
        }
        return ColorCounts(counts, mono, multi, colorless, unknown)
    }

    /** Colour breakdown over every card in the owned decks (each deck's copy counts). */
    fun cumulativeColors(decks: List<Deck>, includeLands: Boolean = false): ColorCounts =
        decks.fold(ColorCounts.EMPTY) { acc, d -> acc + colors(d, includeLands) }

    /** Colour breakdown over unique cards across owned decks (a card shared by 3 decks counts once). */
    fun uniqueColors(decks: List<Deck>, includeLands: Boolean = false): ColorCounts {
        val unique = LinkedHashMap<String, DeckCard>()
        decks.flatMap { allCards(it) }.forEach { unique.putIfAbsent(it.name.lowercase(), it.copy(quantity = 1)) }
        return colorsOf(unique.values.toList(), includeLands)
    }

    /** How often each colour identity (e.g. "BG") is used by the owned decks' commanders. */
    fun identityCounts(decks: List<Deck>): Map<String, Int> =
        decks.groupingBy { it.colorIdentity.ifEmpty { "?" } }.eachCount()

    data class Summary(val decks: Int, val totalCards: Int, val uniqueCards: Int, val sharedCards: Int, val copiesNeeded: Int)

    /** [copiesNeeded] = extra copies needed to build every owned deck at once (non-basic cards). */
    fun summary(decks: List<Deck>): Summary {
        val shared = sharedCards(decks, includeBasics = false)
        val unique = decks.flatMap { allCards(it) }.map { it.name.lowercase() }.toSet().size
        return Summary(
            decks = decks.size,
            totalCards = decks.sumOf { it.cardCount },
            uniqueCards = unique,
            sharedCards = shared.size,
            copiesNeeded = shared.sumOf { it.count - 1 },
        )
    }

    /**
     * Keeps only the cards whose colour identity includes every colour in [colors] ("G" = green cards, "UG" = cards
     * that are both blue and green). "C" means colourless cards only. Empty = no filter. Cards without colour data
     * are dropped while a filter is on.
     */
    fun filterColors(decks: List<Deck>, colors: String): List<Deck> {
        if (colors.isEmpty()) return decks
        fun keep(c: DeckCard): Boolean {
            val id = c.colors ?: return false
            val set = id.filter { it in "WUBRG" }
            return if ('C' in colors) set.isEmpty() else colors.all { it in set }
        }
        return decks.map { d -> d.copy(commanders = d.commanders.filter(::keep), cards = d.cards.filter(::keep)) }
    }

    /** Non-land cards by mana value 0..7+ across [decks]; [unique] counts each card name once. */
    fun curve(decks: List<Deck>, unique: Boolean): List<Int> {
        val out = IntArray(8)
        val cards = decks.flatMap { allCards(it) }.filter { it.type != CardType.LAND }
        val list = if (unique) cards.distinctBy { it.name.lowercase() }.map { it.copy(quantity = 1) } else cards
        list.forEach { c -> out[(c.manaValue ?: 0.0).toInt().coerceIn(0, 7)] += c.quantity }
        return out.toList()
    }

    /** Average mana value of a deck's non-land cards, or null when it has none. */
    fun avgManaValue(deck: Deck): Double? {
        val nl = allCards(deck).filter { it.type != CardType.LAND && it.manaValue != null }
        val n = nl.sumOf { it.quantity }
        return if (n == 0) null else nl.sumOf { it.manaValue!! * it.quantity } / n
    }
}
