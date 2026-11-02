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
data class ManaCost(val pips: List<Int>, val generic: Int) {
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
            Regex("""\{([^}]+)\}""").findAll(face).forEach { m ->
                val sym = m.groupValues[1].uppercase()
                when {
                    sym.toIntOrNull() != null -> generic += sym.toInt()
                    sym == "X" || sym == "Y" || sym == "Z" -> Unit
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
            return ManaCost(pips, generic)
        }
    }
}

/** What a card does in the quick model. A card can have several roles. */
enum class Role { LAND, RAMP_LAND, ROCK, DORK, DRAW, ENGINE, REMOVAL, WIPE, COUNTER, TUTOR, BURN, DRAIN, TOKENS, LIFEGAIN, PROTECTION, RECURSION, CREATURE, OTHER }

/** How a removal spell gets rid of its target. */
enum class RemovalKind { DESTROY, EXILE, BOUNCE, DAMAGE }

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
) {
    val isLand: Boolean get() = Role.LAND in roles
    val isManaSource: Boolean get() = produces != 0 && (Role.LAND in roles || Role.ROCK in roles || Role.DORK in roles)
    val isCreature: Boolean get() = Role.CREATURE in roles
    val isToken: Boolean get() = madeBy != null
    val isArtifact: Boolean get() = type == CardType.ARTIFACT || Role.ROCK in roles
    val isPermanent: Boolean get() = type != CardType.INSTANT && type != CardType.SORCERY

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

    /** True when some parsed ability or activation has an effect of this type. */
    fun parses(kind: Class<out Effect>): Boolean =
        abilities.any { a -> a.effects.any { kind.isInstance(it) } } || activated.any { a -> a.effects.any { kind.isInstance(it) } }

    /** Plain-language list of what the model does with this card. */
    fun describe(): List<String> = buildList {
        if (isCreature) add("${power}/${toughness} creature" + Kw.names(keywords).takeIf { it.isNotEmpty() }?.joinToString(", ", " · ").orEmpty())
        if (isManaSource && !isLand) add("mana: ${ManaColor.letters(produces).ifEmpty { "C" }}")
        if (anthem > 0) add("+$anthem/+$anthem to ${lordType?.let { "${it}s" } ?: "creatures"} you control")
        if (buff > 0) add("+$buff power to one creature")
        roles.filter { it in setOf(Role.RAMP_LAND, Role.REMOVAL, Role.WIPE, Role.COUNTER, Role.TUTOR, Role.PROTECTION, Role.RECURSION) }
            .forEach { add(it.name.lowercase().replace('_', ' ')) }
        abilities.forEach { add(it.describe()) }
        activated.forEach { add(it.describe()) }
    }

    companion object {
        private val numberWords = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "x" to 3,
        )
        private fun countAfter(text: String, pattern: Regex): Int? =
            pattern.find(text)?.groupValues?.getOrNull(1)?.lowercase()?.let { numberWords[it] ?: it.toIntOrNull() }

        private val wipeRx = Regex("(destroy|exile) all |(destroy|exile) each |all creatures get -\\d+/-\\d+|return all nonland permanents")
        private val removalRx = Regex("(destroy|exile) (target|up to (one|two) target)|deals? \\d+ damage to (any target|target creature)|return target (nonland )?(creature|permanent) to its owner's hand|target (creature|player)'s owner shuffles")
        private val counterRx = Regex("counter target (spell|noncreature spell|creature spell|activated)")
        private val rampRx = Regex("search your library for (up to (one|two|three) )?(a |an )?(basic )?lands? cards?.{0,80}(onto|put (it|them) onto) the battlefield")
        private val tutorRx = Regex("search your library for an? card")
        val protectionRx = listOf(
            Regex("""(creatures|permanents|artifacts|other creatures) you control (gain|have|get)[^.]{0,60}(hexproof|indestructible|shroud|protection|ward)"""),
            Regex("""target (creature|permanent|artifact|commander)( or [a-z]+)?( you control)? (gains|has|gets)[^.]{0,60}(hexproof|indestructible|shroud|protection)"""),
            Regex("""(equipped|enchanted) (creature|permanent) (has|gains|gets [^.]{0,20}and has)[^.]{0,40}(hexproof|indestructible|shroud|protection|ward)"""),
            Regex("""phases? out"""),
            Regex("""you have (hexproof|shroud)"""),
            Regex("""counter target spell or ability an opponent controls that targets"""),
        )
        val recursionRx = listOf(
            Regex("""return[^.]{0,80}from (your|a) graveyard to (your hand|the battlefield)"""),
            Regex("""(put|return)[^.]{0,60}from (your|a|any) graveyard onto the battlefield"""),
            Regex("""return (target|up to [a-z]+ target|all|each)[^.]{0,40}cards? from your graveyard"""),
        )
        private val lordRx = Regex("""other ([a-z]+) (creatures )?you control get \+(\d+)/\+\d+""")
        private fun singular(w: String): String = when {
            w.endsWith("ves") -> w.dropLast(3) + "f"
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w == "creatures" || w == "creature" -> "creature"
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
        private val anthemRx = Regex("""(other )?creatures you control get \+(\d+)/\+\d+""")
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
            val o = card.oracle.orEmpty()
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
            val isFetch = type == CardType.LAND && Regex("search your library for an? (basic )?land|search your library for an? (\\w+ or \\w+|\\w+, \\w+,? or \\w+) card").containsMatchIn(text)
            if (isFetch) produces = produces or (deckColors.takeIf { it != 0 } ?: ManaColor.ALL)
            // "Any colour" sources (Command Tower, Arcane Signet) only matter for the deck's own colours.
            if (produces and ManaColor.ALL == ManaColor.ALL && deckColors and ManaColor.ALL != 0) produces = deckColors and ManaColor.ALL
            if ("any color" in text && produces == 0) produces = deckColors.takeIf { it != 0 } ?: ManaColor.ALL

            val entersTapped = type == CardType.LAND && (isFetch ||
                (Regex("enters( the battlefield)? tapped").containsMatchIn(text) && "unless" !in text && "you may pay" !in text))

            val roles = mutableSetOf<Role>()
            if (type == CardType.LAND) roles += Role.LAND
            val creature = "Creature" in typeLine.substringBefore("//") || (commander && type == CardType.CREATURE)
            if (creature) roles += Role.CREATURE
            if (type != CardType.LAND && produces != 0) roles += if (creature) Role.DORK else Role.ROCK
            if (type != CardType.LAND && rampRx.containsMatchIn(text)) roles += Role.RAMP_LAND
            if ("ramp" in tags && type != CardType.LAND && roles.none { it == Role.ROCK || it == Role.DORK }) roles += Role.RAMP_LAND
            if (wipeRx.containsMatchIn(text) || "wipe" in tags) roles += Role.WIPE
            else if (removalRx.containsMatchIn(text) || "removal" in tags) roles += Role.REMOVAL
            if (counterRx.containsMatchIn(text) || "counter" in tags) roles += Role.COUNTER
            val drawsOnCast = countAfter(text, Regex("draws? (a|an|one|two|three|four|five|six|seven|x|\\d+) cards?"))
            val engine = Regex("(at the beginning of your (upkeep|draw step|end step)|whenever [^.]{0,80}),? (you may )?draw").containsMatchIn(text)
            if (engine) roles += Role.ENGINE else if (drawsOnCast != null || "draw" in tags) roles += Role.DRAW
            if (tutorRx.containsMatchIn(text) || "tutor" in tags) roles += Role.TUTOR
            val burn = countAfter(text, Regex("deals? (\\d+|x) damage to (any target|target player|target opponent|each opponent)"))
            if (burn != null && "each opponent" in text) roles += Role.DRAIN
            else if (burn != null || "burn" in tags) roles += Role.BURN
            if (Regex("each opponent loses (\\d+|x|one|two|three) life").containsMatchIn(text)) roles += Role.DRAIN
            if (Regex("(?<!its controller )create (a|an|one|two|three|four|x|\\d+) [^.]{0,40}tokens?").containsMatchIn(text)) roles += Role.TOKENS
            if ("lifegain" in tags || Regex("you gain (\\d+|x) life").containsMatchIn(text)) roles += Role.LIFEGAIN
            if ("protection" in tags || protectionRx.any { it.containsMatchIn(text) }) roles += Role.PROTECTION
            if ("recursion" in tags || recursionRx.any { it.containsMatchIn(text) }) roles += Role.RECURSION
            if (roles.isEmpty() || roles == setOf(Role.LIFEGAIN)) roles += Role.OTHER

            val kwText = card.keywords.orEmpty().joinToString(", ") { it.lowercase() }
            var keywords = Kw.of(kwText) and Kw.WARD.inv() or (if ("ward" in kwText) Kw.WARD else 0)
            val norm = EffectParser.normalise(card.name, o)
            if (Regex("""~ can't be blocked""").containsMatchIn(norm)) keywords = keywords or Kw.UNBLOCKABLE
            if (Regex("""~ can't block""").containsMatchIn(norm)) keywords = keywords or Kw.CANT_BLOCK
            fun stat(v: String?) = v?.toIntOrNull() ?: if (v != null) maxOf(1, mv - 1) else 0
            val drainAmount = countAfter(text, Regex("each opponent loses (\\d+|x|one|two|three) life"))
            val tokenCount = countAfter(text, Regex("create (a|an|one|two|three|four|x|\\d+) [^.]{0,40}tokens?")) ?: 0
            val tokenPower = Regex("(\\d+)/(\\d+)[^.]{0,30}token").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val lord = lordRx.find(text)?.takeIf { singular(it.groupValues[1]) != "creature" }
            val anthem = (lord?.groupValues?.get(3) ?: anthemRx.find(text)?.groupValues?.get(2))?.toIntOrNull()?.coerceAtMost(3) ?: 0
            val removalKind = when {
                Regex("exile (target|all|each|up to)").containsMatchIn(text) -> RemovalKind.EXILE
                Regex("return target [a-z ]+ to its owner's hand").containsMatchIn(text) -> RemovalKind.BOUNCE
                Regex("deals? (\\d+|x) damage to (any target|target creature)").containsMatchIn(text) -> RemovalKind.DAMAGE
                else -> RemovalKind.DESTROY
            }
            val removalDamage = countAfter(text, Regex("deals? (\\d+|x) damage to (any target|target creature)")) ?: 0
            val subtypes = typeLine.substringBefore("//").substringAfter("—", "").lowercase().split(' ').filter { it.isNotBlank() }.toSet()

            // Which lines the role-based effects already cover (for the coverage report).
            fun roleLine(line: String): Boolean = wipeRx.containsMatchIn(line) || removalRx.containsMatchIn(line) ||
                counterRx.containsMatchIn(line) || rampRx.containsMatchIn(line) || tutorRx.containsMatchIn(line) ||
                anthemRx.containsMatchIn(line) || lordRx.containsMatchIn(line) || buffRx.containsMatchIn(line) ||
                protectionRx.any { it.containsMatchIn(line) } || recursionRx.any { it.containsMatchIn(line) } ||
                Regex("""~ can't (be blocked|block)|add \{|enters tapped|enters the battlefield tapped|^as ~ enters|for each|you may play|can't be countered""").containsMatchIn(line)
            val parsed = if (type == CardType.LAND) null else
                EffectParser.parse(card.name, card.oracle, permanent = type != CardType.INSTANT && type != CardType.SORCERY, roleLine = ::roleLine)

            return SimCard(
                name = card.name, type = type, cost = cost, mv = maxOf(mv, cost.total), roles = roles,
                produces = produces, entersTapped = entersTapped,
                power = if (creature) stat(card.power).let { if (card.power == null) maxOf(1, mv - 1) else it } else 0,
                toughness = if (creature) stat(card.toughness).let { if (card.toughness == null) maxOf(1, mv) else it } else 0,
                keywords = keywords,
                anthem = anthem, lordType = lord?.groupValues?.get(1)?.let(::singular),
                buff = buffRx.find(text)?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(6) ?: 0,
                draws = when {
                    engine -> 1
                    else -> (drawsOnCast ?: if (Role.DRAW in roles) 2 else 0).coerceIn(0, 5)
                },
                damage = (drainAmount ?: burn ?: 0).coerceIn(0, 10),
                tokenPower = tokenCount.coerceIn(0, 6) * tokenPower,
                isCommander = commander,
                subtypes = subtypes, removalKind = removalKind, removalDamage = removalDamage,
                recursionToBattlefield = Regex("graveyard (onto|to) the battlefield").containsMatchIn(text),
                abilities = parsed?.abilities.orEmpty(), activated = parsed?.activated.orEmpty(),
                coverage = parsed?.coverage ?: Coverage(emptyList(), emptyList()),
            )
        }
    }
}
