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
            Regex("""\{([^}]+)\}""").findAll(face).forEach { m ->
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
) {
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
        abilities = abilities.filter { a -> a.effects.none { it is CopyCreature } }, activated = activated, coverage = coverage,
        madeBy = if (token) via.name else null,
        manaAmount = manaAmount, manaScale = manaScale, manaActivation = manaActivation, tutor = tutor, pump = pump, equipCost = equipCost,
        grants = grants, isAura = isAura, reduceFor = reduceFor, reduceBy = reduceBy, toxic = toxic, loyalty = loyalty, chapters = chapters,
        teamGrants = teamGrants, teamGrantsOthers = teamGrantsOthers, tokenMultiplier = tokenMultiplier, extraSquirrels = extraSquirrels,
        lordGrants = lordGrants, landwalk = landwalk, lordLandwalk = lordLandwalk, counterPlus = counterPlus, counterMultiplier = counterMultiplier,
        exploreTwice = exploreTwice, landManaExtra = landManaExtra, cascade = cascade,
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

        private val wipeRx = Regex("(destroy|exile) all |(destroy|exile) each |all creatures get -\\d+/-\\d+|return all nonland permanents")
        private val removalRx = Regex("(destroy|exile) (target|up to (one|two) target)(?![a-z ]*card)|deals? \\d+ damage to (any target|target creature)|return target (nonland )?(creature|permanent) to its owner's hand|target (creature|player)'s owner shuffles|target creature( an opponent controls| you don't control)? gets -\\d+/-\\d+|fights (target|up to one target|another target) creature")
        private val counterRx = Regex("counter target (spell|noncreature spell|creature spell|activated)")
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
                Regex("(put|onto) (it|that card|them)? ?onto the battlefield|put it onto the battlefield").containsMatchIn(after.take(200)) -> TutorDest.BATTLEFIELD
                Regex("(put|puts) (it|that card|them) into (your|its owner's) graveyard").containsMatchIn(after.take(200)) -> TutorDest.GRAVEYARD
                "on top of your library" in after.take(200) || "on top" in after.take(160) -> TutorDest.TOP
                else -> TutorDest.HAND
            }
            return TutorSpec(types, maxMv, dest)
        }
        private val pumpRx = Regex("target creature gets \\+(\\d+)/\\+(\\d+) until end of turn")
        private val reduceRx = Regex("(creature |artifact |noncreature |instant and sorcery |instant or sorcery |enchantment )?spells (?:you cast )?(?:of the chosen type )?cost \\{(\\d+)\\} less")
        private val walkWords = mapOf("islandwalk" to ManaColor.U, "forestwalk" to ManaColor.G, "swampwalk" to ManaColor.B, "mountainwalk" to ManaColor.R, "plainswalk" to ManaColor.W)
        private fun walks(text: String) = walkWords.entries.fold(0) { m, (w, c) -> if (w in text) m or c else m }
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
        private val lordRx = Regex("""(?:other ([a-z]+) (?:creatures )?(?:you control )?|([a-z]+) creature tokens you control |creature (tokens) you control )get \+(\d+)/\+\d+(?: and have ([a-z ,]+))?""")
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
            val tutor = tutorSpec(text)
            if (tutor != null || "tutor" in tags) roles += Role.TUTOR
            val pump = if (type == CardType.INSTANT) pumpRx.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
            if (pump > 0) roles += Role.TRICK
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
            var keywords = Kw.of(kwText)
            val norm = EffectParser.normalise(card.name, o)
            if (Regex("""~ can't be blocked""").containsMatchIn(norm)) keywords = keywords or Kw.UNBLOCKABLE
            if (Regex("""~ can't block""").containsMatchIn(norm)) keywords = keywords or Kw.CANT_BLOCK
            fun stat(v: String?) = v?.toIntOrNull() ?: if (v != null) maxOf(1, mv - 1) else 0
            val drainAmount = countAfter(text, Regex("each opponent loses (\\d+|x|one|two|three) life"))
            val tokenCount = countAfter(text, Regex("create (a|an|one|two|three|four|x|\\d+) [^.]{0,40}tokens?")) ?: 0
            val tokenPower = Regex("(\\d+)/(\\d+)[^.]{0,30}token").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            // Anthems only from always-on text: not "until end of turn", not activated or triggered abilities.
            val staticText = text.split('\n').filter { l ->
                "until end of turn" !in l && ':' !in l && !l.trimStart().startsWith("when") && !l.trimStart().startsWith("at the beginning")
            }.joinToString("\n")
            val lord = lordRx.find(staticText)?.takeIf { m -> m.groupValues[1].isEmpty() || singular(m.groupValues[1]) != "creature" }
            val lordType = lord?.let { m ->
                when {
                    m.groupValues[3].isNotEmpty() || m.groupValues[2].isNotEmpty() -> "token"
                    else -> singular(m.groupValues[1])
                }
            }
            // Keyword-only lords: "Horrors you control have menace", "All Rats have fear", "Other Merfolk have islandwalk".
            val kwLord = Regex("""(?:all |other )?([a-z]+?)s? (?:creatures )?(?:you control )?have ([a-z ,]+)""").find(staticText)
                ?.takeIf { m -> m.groupValues[1] !in setOf("creature", "permanent", "land", "artifact", "it", "they", "spell", "you") && '"' !in m.value }
            val lordGrantText = lord?.groupValues?.get(5)?.ifBlank { null } ?: kwLord?.groupValues?.get(2).orEmpty()
            val anthem = (lord?.groupValues?.get(4) ?: anthemRx.find(staticText)?.groupValues?.get(2))?.toIntOrNull()?.coerceAtMost(3) ?: 0
            val teamMatch = Regex("""(other )?(creatures|permanents) you control have ([a-z ,]+)""").find(staticText)
            val teamGrants = teamMatch?.groupValues?.get(3)?.takeIf { '"' !in it }?.let { Kw.of(it) } ?: 0
            val teamGrantsOthers = teamMatch?.groupValues?.get(1)?.isNotEmpty() == true
            val tokenMultiplier = if (Regex("""tokens? would be created under your control, twice that many|creates? twice that many of those tokens""").containsMatchIn(text)) 2 else 1
            val extraSquirrels = "plus that many 1/1 green squirrel" in text
            val counterMultiplier = if (Regex("""twice that many (of those )?(\+1/\+1 )?counters""").containsMatchIn(text)) 2 else 1
            val counterPlus = if (Regex("""that many plus one \+1/\+1 counters""").containsMatchIn(text)) 1 else 0
            val removalKind = when {
                Regex("fights (target|up to one target|another target) creature").containsMatchIn(text) -> RemovalKind.FIGHT
                Regex("target creature( an opponent controls| you don't control)? gets -\\d+/-\\d+").containsMatchIn(text) -> RemovalKind.MINUS
                Regex("exile (target|all|each|up to)").containsMatchIn(text) -> RemovalKind.EXILE
                Regex("return target [a-z ]+ to its owner's hand").containsMatchIn(text) -> RemovalKind.BOUNCE
                Regex("deals? (\\d+|x) damage to (any target|target creature)").containsMatchIn(text) -> RemovalKind.DAMAGE
                else -> RemovalKind.DESTROY
            }
            val removalDamage = countAfter(text, Regex("deals? (\\d+|x) damage to (any target|target creature)"))
                ?: Regex("gets -(\\d+)/-(\\d+)").find(text)?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val subtypes = typeLine.substringBefore("//").substringAfter("—", "").lowercase().split(' ').filter { it.isNotBlank() }.toSet()

            // Which lines the role-based effects already cover (for the coverage report).
            fun roleLine(line: String): Boolean = wipeRx.containsMatchIn(line) || removalRx.containsMatchIn(line) ||
                counterRx.containsMatchIn(line) || rampRx.containsMatchIn(line) || tutorRx.containsMatchIn(line) ||
                anthemRx.containsMatchIn(line) || lordRx.containsMatchIn(line) || buffRx.containsMatchIn(line) ||
                protectionRx.any { it.containsMatchIn(line) } || recursionRx.any { it.containsMatchIn(line) } ||
                pumpRx.containsMatchIn(line) || reduceRx.containsMatchIn(line) || tutorRx.containsMatchIn(line) ||
                Regex("""(equipped|enchanted) creature (gets|has)""").containsMatchIn(line) ||
                Regex("""tokens? would be created under your control|twice that many|that many plus one|(creatures|permanents) you control have|explores again|tap a (land|permanent) for mana""").containsMatchIn(line) ||
                Regex("""^(all |other )?[a-z]+s? (you control )?have [a-z ,]+\.?$|you may look at the top card of your library""").containsMatchIn(line) ||
                Regex("""~ can't (be blocked|block)|add \{|enters tapped|enters the battlefield tapped|^as ~ enters|for each|you may play|can't be countered""").containsMatchIn(line)
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
                    else -> Regex("""\{[wubrgc]\}""").findAll(what.substringBefore(" or ")).count().coerceAtLeast(1)
                }
                if ("for each" in what || "equal to" in what) manaScale = EffectParser.scale(what)
                manaActivation = Regex("""\{(\d+)\}""").find(manaLine.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val equip = Regex("""\bequip \{(\d+)\}""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (Regex("""\bequip\b""").containsMatchIn(text) && "Equipment" in typeLine) 2 else null
            val isAura = "Aura" in typeLine
            val grantLine = Regex("""(equipped|enchanted) creature (?:gets [^.]*? and )?(?:has|gains) ([a-z, ]+)""").find(text)?.groupValues?.get(2).orEmpty()
            val reduce = reduceRx.find(text)

            return SimCard(
                name = card.name, type = type, cost = cost, mv = maxOf(mv, cost.total), roles = roles,
                produces = produces, entersTapped = entersTapped,
                power = if (creature) stat(card.power).let { if (card.power == null) maxOf(1, mv - 1) else it } else 0,
                toughness = if (creature) stat(card.toughness).let { if (card.toughness == null) maxOf(1, mv) else it } else 0,
                keywords = keywords,
                anthem = anthem, lordType = lordType ?: kwLord?.groupValues?.get(1)?.let { singular(it) },
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
                manaAmount = manaAmount, manaScale = manaScale, manaActivation = manaActivation,
                tutor = tutor, pump = pump, equipCost = equip, grants = Kw.of(grantLine), isAura = isAura,
                reduceFor = reduce?.groupValues?.get(1)?.trim(), reduceBy = reduce?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                toxic = Regex("""\btoxic (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                loyalty = parsed?.loyalty.orEmpty(), chapters = parsed?.chapters.orEmpty(),
                teamGrants = teamGrants, teamGrantsOthers = teamGrantsOthers, tokenMultiplier = tokenMultiplier, extraSquirrels = extraSquirrels,
                lordGrants = Kw.of(lordGrantText), landwalk = walks(kwText), lordLandwalk = walks(lordGrantText),
                counterPlus = counterPlus, counterMultiplier = counterMultiplier,
                exploreTwice = Regex("""explores, then (it )?explores again""").containsMatchIn(text),
                // "Cascade, cascade" (Maelstrom Wanderer) counts twice.
                cascade = text.lines().filter { it.trim().startsWith("cascade") }.sumOf { l -> Regex("""\bcascade\b""").findAll(l).count() }
                    .let { k -> if (k == 0 && card.keywords.orEmpty().any { it.equals("cascade", true) }) 1 else k },
                landManaExtra = if (Regex("""whenever you tap a (land|permanent) for mana, add (one|an additional) (additional )?mana""").containsMatchIn(text)) 1 else 0,
            )
        }
    }
}
