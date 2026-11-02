package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.EndCondition
import kotlin.math.max
import kotlin.random.Random

/*
 * The quick model: a fast Commander game model built on each card's real Scryfall data.
 *
 *  - Mana: costs are paid pip by pip from the sources in play (lands, rocks, dorks, Treasure) with their real colours;
 *    tapped lands, fetches and land ramp; land drops fix the colours the hand needs.
 *  - Cards: rules text is read once per run (CardEffects.kt) into triggered abilities (enters, attacks, upkeep, end
 *    step, landfall, casting, artifacts/creatures entering, dying), activated abilities and effects (tokens with real
 *    size and keywords, Treasure/Food/Clue, draw, drain, burn, life, +1/+1 counters), plus role-based removal, wipes,
 *    counterspells, protection, recursion, ramp and tutors.
 *  - Combat: real power/toughness, counters, anthems and tribal lords; flying/reach, first/double strike, deathtouch,
 *    trample, lifelink, menace, vigilance, indestructible, hexproof, defender, "can't block"; attackers tap and can't
 *    block on the next turns; blockers are chosen to survive, trade up or prevent lethal.
 *  - Interaction: instant removal and counterspells are held up and used on opponents' turns; protection spells save
 *    a targeted creature; hexproof and indestructible are respected; exile vs destroy vs bounce.
 * Not modelled: the stack beyond one response, politics, most combos, copy effects, and anything the card-text reader
 * reports as not understood.
 */

/** Round cap of the quick model. */
internal const val QUICK_MAX_ROUNDS = 18

/** Pool entries made by Treasure carry this bit, so they're spent last and counted when used. */
private const val TREASURE = 64

/** Prepared deck: every card's model, by deck id. */
internal class QuickDeck(val deck: Deck, val library: List<SimCard>, val commanders: List<SimCard>, val colors: Int) {
    /** Known combos this deck has every piece of. */
    val combos: List<Combo> = Combos.inDeck((library + commanders).map { it.name.substringBefore(" // ").lowercase() }.toSet())
    /** The deck's most common creature type: what "choose a creature type" cards pick. */
    val mainTribe: String? = (library + commanders).filter { it.isCreature }.flatMap { it.subtypes }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        fun from(deck: Deck): QuickDeck {
            val colors = ManaColor.mask(deck.colorIdentity)
            val lib = deck.cards.flatMap { c -> SimCard.from(c, colors).let { sc -> List(c.quantity) { sc } } }
            return QuickDeck(deck, lib, deck.commanders.map { SimCard.from(it, colors, commander = true) }, colors)
        }
    }
}

/** A permanent on the battlefield. */
private class Perm(val card: SimCard, var sick: Boolean) {
    var tapped = false
    var counters = 0
    /** From anthems, lords and attached equipment/auras; recomputed when they change. */
    var bonus = 0
    /** Combat tricks, until end of turn. */
    var temp = 0
    /** Keywords from attached equipment/auras and team-wide effects. */
    var granted = 0
    /** Keywords until end of turn (pumps). */
    var tempKw = 0
    /** Landwalk colours from lords. */
    var grantedWalk = 0
    /** For equipment and auras: the creature it's on. */
    var attachedTo: Perm? = null
    var loyalty = 4
    var chapter = 0
    /** "Power and toughness equal to the number of …", recounted when the board changes. */
    var dyn = 0
    /** Echo still to pay on the next turn. */
    var echoDue = false
    /** Unearthed or encored: exiled at the end of the turn. */
    var exileAtEnd = false
    var monstrous = false
    var level = 1
    /** Charge, quest, hour… counters (spent by abilities that remove them). */
    var marks = 0
    /** Keywords from keyword counters (flying, menace, shield…): kept while it stays on the battlefield. */
    var kwCounters = 0
    /** Curses: the player this enchants. */
    var cursed: Seat? = null
    val power: Int get() = max(0, card.power + counters + bonus + temp + dyn)
    val toughness: Int get() = max(1, card.toughness + counters + bonus + temp + dyn)
    val token: Boolean get() = card.isToken
    fun has(bit: Int) = card.has(bit) || (granted or tempKw or kwCounters) and bit != 0
    val flying get() = has(Kw.FLYING)
    val reach get() = has(Kw.REACH)
    val trample get() = has(Kw.TRAMPLE)
    val deathtouch get() = has(Kw.DEATHTOUCH)
    val lifelink get() = has(Kw.LIFELINK)
    val menace get() = has(Kw.MENACE)
    val unblockable get() = has(Kw.UNBLOCKABLE)
    val doubleStrike get() = has(Kw.DOUBLE_STRIKE)
    val firstStrike get() = has(Kw.FIRST_STRIKE) || has(Kw.DOUBLE_STRIKE)
}

private class Seat(val index: Int, val deck: QuickDeck, rng: Random, seats: Int) {
    val library = ArrayDeque(deck.library.shuffled(rng))
    val hand = mutableListOf<SimCard>()
    val graveyard = mutableListOf<SimCard>()
    val lands = mutableListOf<Perm>()
    val rocks = mutableListOf<Perm>()
    val creatures = mutableListOf<Perm>()
    val others = mutableListOf<Perm>()
    val commandZone = deck.commanders.toMutableList()
    val cmdCasts = HashMap<String, Int>()
    val cmdDamageFrom = IntArray(seats)
    var treasures = 0
    var foods = 0
    var clues = 0
    var poison = 0
    var lifeGainedTurn = 0
    var extraTurns = 0
    var extraCombats = 0
    /** Lands drawn so far, opening hand included. */
    var landsSeen = 0
    var life = 40
    var alive = true
    /** Mana available on this player's turn, as colour masks. */
    val pool = mutableListOf<Int>()
    /** Mana left untapped at the end of this player's turn, usable on opponents' turns. */
    val open = mutableListOf<Int>()
    /** Once-per-turn abilities already used this turn. */
    val usedOnce = HashSet<Pair<Perm, Int>>()
    /** Triggered abilities left this turn (guards against loops). */
    var triggerBudget = 40
    /** Spells cast this turn (storm). */
    var spellsThisTurn = 0
    /** Rebound spells waiting for the next upkeep. */
    val rebounds = mutableListOf<SimCard>()
    /** The last instant or sorcery this player cast (for copy effects). */
    var lastSpell: SimCard? = null
    /** Suspended cards and the turns left until they're cast. */
    val suspended = mutableListOf<Pair<SimCard, Int>>()
    /** The board's always-on effects, recomputed when permanents change (cheap to check every time). */
    fun has(test: (Statics) -> Boolean) = creatures.any { test(it.card.statics) } || others.any { test(it.card.statics) } || rocks.any { test(it.card.statics) }
    val track = PlayerTrack(seats)

    val boardPower get() = creatures.sumOf { it.power }
    fun allSources(): List<Perm> = lands + rocks + creatures.filter { it.card.isManaSource }
    fun permanents(): List<Perm> = lands + rocks + creatures + others
    fun nonLands(): List<Perm> = creatures + others + rocks
}

/** Pays [cost] from [pool]: scarce pips first, each from the least flexible source. Returns false if it can't. */
internal fun payCost(cost: ManaCost, pool: MutableList<Int>, commit: Boolean): Boolean {
    if (cost.total > pool.size) return false
    val left = pool.toMutableList()
    for (pip in cost.pips.sortedBy { Integer.bitCount(it) }) {
        val i = left.indices.filter { left[it] and pip != 0 }.minByOrNull { Integer.bitCount(left[it]) } ?: return false
        left.removeAt(i)
    }
    if (left.size < cost.generic) return false
    if (commit) {
        // Generic is paid with the least useful sources (Treasure last).
        repeat(cost.generic) { left.remove(left.minByOrNull { Integer.bitCount(it and (ManaColor.ALL or TREASURE)) }!!) }
        pool.clear()
        pool.addAll(left)
    }
    return true
}

internal class QuickGame(private val decks: List<QuickDeck>, private val rng: Random) {
    private val n = decks.size
    private val seats = decks.mapIndexed { i, d -> Seat(i, d, rng, n) }
    private var round = 0
    private var lastCause: EndCondition? = null
    /** Index (into MAX_TURNS series) of the turn being played, for per-round stats. */
    private var turnIdx = 0
    /** X paid for the spell resolving now (0 = none). */
    private var castX = 0
    /** Whose turn it is (their pool pays optional costs; others use their open mana). */
    private var active: Seat? = null
    /** The life just gained, for "whenever you gain life, … that much". */
    private var lastGain = 0
    /** The last combat damage to a player, for "that player mills that many". */
    private var lastDamage = 0
    private var lastDamaged: Seat? = null
    /** The player being attacked in the current combat. */
    private var defending: Seat? = null
    /** Who is the monarch (draws a card at their end step). */
    private var monarch: Seat? = null
    /** Times the spell resolving now was kicked. */
    private var kicks = 0
    /** Free casts in progress (guards against chains of free spells). */
    private var freeDepth = 0
    /** The card an opponent just sacrificed or discarded, and whose it was (for Tergrid-style triggers). */
    private var lostCard: SimCard? = null
    private var lostFrom: Seat? = null
    /** The spell resolving now was overloaded: its removal hits every legal target. */
    private var overloaded = false
    /** Creatures borrowed until end of turn (Threaten), with their owners. */
    private val borrowed = mutableListOf<Pair<Perm, Seat>>()

    fun play(): GameResult {
        seats.forEach { openingHand(it) }
        var winner: Int? = null
        loop@ while (round < QUICK_MAX_ROUNDS) {
            for (p in seats) {
                if (!p.alive) continue
                takeTurn(p)
                // Extra turns (at most two in a row, so loops can't run away).
                var extra = 0
                while (p.alive && p.extraTurns > 0 && extra < 2 && seats.count { it.alive } > 1) {
                    p.extraTurns--; extra++
                    takeTurn(p)
                }
                p.extraTurns = 0
                val alive = seats.filter { it.alive }
                if (alive.size <= 1) {
                    winner = alive.firstOrNull()?.index
                    break@loop
                }
            }
            for (p in seats) if (round < MAX_TURNS) p.track.lifeByTurn[round] = if (p.alive) p.life else 0
            round++
        }
        if (round < MAX_TURNS) for (p in seats) p.track.lifeByTurn[round] = if (p.alive) p.life else 0
        val alive = seats.filter { it.alive }
        var adjudicated = false
        fun score(s: Seat) = s.life + 2 * s.boardPower + s.permanents().size
        if (winner == null && alive.size >= 2) {
            // Round limit: score on life and board, like the Forge games.
            val scored = alive.sortedByDescending { score(it) }
            if (score(scored[0]) != score(scored[1])) { winner = scored[0].index; adjudicated = true }
            scored.forEachIndexed { i, s -> s.track.place = i + 1 }
        } else if (winner != null) {
            seats[winner].track.place = 1
        }
        seats.forEach { it.track.manaRecorded = true }
        return GameResult(
            winnerSeat = winner,
            winCondition = when {
                winner == null -> EndCondition.TIMEOUT
                adjudicated -> EndCondition.ADJUDICATED
                else -> lastCause
            },
            rounds = minOf(round + 1, QUICK_MAX_ROUNDS),
            tracks = seats.map { it.track },
            adjudicated = adjudicated,
        )
    }

    // ───────────────────────────── Cards in and out ─────────────────────────────

    private fun draw(p: Seat, count: Int, counted: Boolean = true) {
        repeat(count) {
            val c = p.library.removeFirstOrNull()
            if (c == null) {
                val maniac = p.nonLands().firstOrNull { perm -> perm.card.abilities.any { a -> a.effects.any { it == WinGame(WinCondition.EMPTY_LIBRARY) } } }
                if (maniac != null) win(p, maniac.card, "${maniac.card.name}|${p.index}") else eliminate(p, EndCondition.MILL, null)
                return
            }
            p.hand += c
            if (c.isLand) p.landsSeen++
            val ct = p.track.card(c.name)
            if (!ct.seen) { ct.seen = true; ct.drawnRound = round }
            if (counted) { p.track.drawn++; p.track.drawsByTurn[turnIdx]++ }
            if (counted && p.alive) fire(p, Trigger.DRAW)
        }
    }

    private fun openingHand(p: Seat) {
        var mulls = 0
        while (true) {
            repeat(7) { p.library.removeFirstOrNull()?.let { p.hand += it } }
            val lands = p.hand.count { it.isLand }
            // Keep 2–5 landers whose lands make at least one colour the hand needs; mulligan at most twice.
            val colors = p.hand.filter { it.isLand }.fold(0) { m, c -> m or c.produces }
            val needs = p.hand.filter { !it.isLand }.fold(0) { m, c -> m or c.cost.colorsNeeded } and ManaColor.ALL
            val ok = lands in 2..5 && (needs == 0 || colors and needs != 0)
            if (ok || mulls >= 2) break
            p.library.addAll(p.hand); p.hand.clear()
            val reshuffled = p.library.shuffled(rng); p.library.clear(); p.library.addAll(reshuffled)
            mulls++
        }
        // First mulligan is free in Commander; bottom one card per extra mulligan (the least useful).
        repeat(max(0, mulls - 1)) {
            val worst = p.hand.maxByOrNull { if (it.isLand) -1 else it.mv } ?: return@repeat
            p.hand.remove(worst); p.library.addLast(worst)
        }
        p.track.mulligans = mulls
        p.landsSeen = p.hand.count { it.isLand }
        p.hand.forEach { val ct = p.track.card(it.name); ct.seen = true; ct.inOpening = true; ct.drawnRound = 0 }
    }

    private fun noteCast(p: Seat, c: SimCard) {
        val ct = p.track.card(c.name)
        ct.casts++
        if (ct.firstCastRound < 0) ct.firstCastRound = turnIdx + 1
    }

    // ───────────────────────────── Turn ─────────────────────────────

    private fun takeTurn(p: Seat) {
        val t = (p.track.turnsTaken).coerceAtMost(MAX_TURNS - 1)
        turnIdx = t
        active = p
        p.track.turnsTaken++
        for (q in seats) { q.usedOnce.clear(); q.triggerBudget = 40; q.lifeGainedTurn = 0; q.spellsThisTurn = 0 }
        p.permanents().forEach { it.tapped = false; it.sick = false }
        // Seedborn Muse: the other players' permanents untap too, so they have their mana on this turn.
        for (q in seats) if (q !== p && q.alive && q.has { it.untapEachTurn }) {
            q.permanents().forEach { it.tapped = false }
            q.open.clear(); q.allSources().forEach { src -> q.open += (src.card.produces.takeIf { it != 0 } ?: ManaColor.C) }
        }
        fire(p, Trigger.UPKEEP)
        // Events the model doesn't track ("whenever a card is put into an opponent's graveyard"): about every other turn.
        if (rng.nextBoolean()) fire(p, Trigger.SOMETIMES)
        // Suspend: one time counter off each turn; the last one casts it for free.
        if (p.suspended.isNotEmpty()) {
            val ready = p.suspended.filter { it.second <= 1 }.map { it.first }
            p.suspended.replaceAll { it.first to it.second - 1 }
            p.suspended.removeAll { it.second <= 0 }
            for (c in ready) { if (!p.alive) break; noteCast(p, c); castTriggers(p, c); resolve(p, c) }
        }
        for (q in seats) if (q.alive) { if (q !== p) fire(q, Trigger.OPP_UPKEEP); fire(q, Trigger.EACH_UPKEEP) }
        // Rebound: cast again for free, then to the graveyard.
        if (p.rebounds.isNotEmpty()) {
            val again = p.rebounds.toList(); p.rebounds.clear()
            for (c in again) { if (!p.alive) break; noteCast(p, c); castTriggers(p, c); resolve(p, c) }
        }
        if (!p.alive) return
        sagasAndWalkers(p)
        // Draw engines whose text wasn't read as a draw trigger keep a simple "1 card a turn".
        p.nonLands().filter { Role.ENGINE in it.card.roles && !it.card.parses(Draw::class.java) }.forEach { draw(p, it.card.draws.coerceAtLeast(1)) }
        if (!p.alive) return
        p.track.handByTurn[t] = p.hand.size
        if (!p.has { it.skipDraw }) draw(p, 1)
        if (!p.alive) return

        playLand(p)
        refillPool(p)
        // Echo: pay it again or sacrifice the permanent.
        for (perm in p.nonLands().filter { it.echoDue }) {
            perm.echoDue = false
            if (!payCost(perm.card.echo ?: continue, p.pool, commit = true)) die(p, perm, null, perm.card, sacrificed = true)
        }

        // Mana bookkeeping (what this deck's mana base can do this turn).
        val sources = p.allSources()
        p.track.colorsByTurn[t] = sources.fold(0) { m, s -> m or s.card.produces } and ManaColor.ALL
        p.track.sourcesByTurn[t] = sources.size
        p.commandZone.firstOrNull()?.let { cmd ->
            if (p.track.cmdCastableTurn < 0 && payCost(withTax(p, cmd), p.pool, commit = false)) p.track.cmdCastableTurn = t + 1
        }
        if (t < 8) {
            var screwed = false
            for (c in p.hand.distinct()) {
                if (c.isLand || c.cost.total == 0) continue
                if (c.cost.total <= p.pool.size && !payCost(c.cost, p.pool, commit = false)) {
                    p.track.card(c.name).colorLockedTurns++
                    screwed = true
                }
            }
            if (screwed) p.track.colorScrewTurns++
        }
        if (t == 7) p.track.landsDrawnBy8 = p.landsSeen

        castSpells(p)
        if (!p.alive) return
        equipAll(p)
        combat(p)
        var combats = 0
        while (p.alive && p.extraCombats > 0 && combats < 2) {
            p.extraCombats--; combats++
            p.creatures.forEach { it.tapped = false }
            combat(p)
        }
        p.extraCombats = 0
        for (q in seats) q.creatures.forEach { it.temp = 0; it.tempKw = 0 }
        if (!p.alive) return
        spendLeftover(p)
        fire(p, Trigger.END_STEP)
        if (!p.alive) return
        if (monarch === p) draw(p, 1)
        returnBorrowed(p)
        p.nonLands().filter { it.exileAtEnd }.forEach { leave(p, it, null, it.card, toGraveyard = false) }
        if (!p.alive) return

        p.treasures = p.pool.count { it and TREASURE != 0 }
        p.open.clear(); p.open.addAll(p.pool)
        if (p.nonLands().none { it.card.noMaxHand }) while (p.hand.size > 7) {
            val worst = p.hand.filter { !it.isLand }.maxByOrNull { it.mv } ?: p.hand.last()
            p.hand.remove(worst); p.graveyard += worst; p.track.discarded++
            lostACard(p, worst)
        }
        p.track.maxHand = max(p.track.maxHand, p.hand.size)
        p.track.permsByTurn[t] = p.permanents().size
        p.track.creaturesByTurn[t] = p.creatures.size
        p.track.landsInPlayByTurn[t] = p.lands.size
    }

    private fun withTax(p: Seat, cmd: SimCard): ManaCost {
        val tax = 2 * (p.cmdCasts[cmd.name] ?: 0)
        return cmd.cost.copy(generic = cmd.cost.generic + tax)
    }

    private fun refillPool(p: Seat) {
        p.pool.clear()
        val landExtra = p.nonLands().sumOf { it.card.landManaExtra }
        val any = (p.deck.colors and ManaColor.ALL).let { if (it == 0) ManaColor.ALL else it }
        // Chromatic Lantern: every land makes any of the deck's colours; Abundant Growth fixes one land each.
        val rainbow = p.nonLands().any { it.card.landsAnyColor }
        var fixes = p.nonLands().count { it.card.fixesOneLand }
        for (s in p.allSources()) {
            if (s.tapped || (s.card.isCreature && s.sick)) continue
            var color = s.card.produces.takeIf { it != 0 } ?: ManaColor.C
            if (s.card.isLand && (rainbow || fixes-- > 0)) color = color or any
            val extra = if (s.card.isLand) landExtra else 0
            val amount = (s.card.manaScale?.let { count(p, it) } ?: 1) * s.card.manaAmount - s.card.manaActivation + extra
            repeat(amount.coerceIn(if (s.card.manaActivation > 0) 0 else 1, 30)) { p.pool += color }
        }
        // Auras like Wild Growth: one extra mana each while there's a land to tap.
        if (p.lands.isNotEmpty()) p.nonLands().forEach { perm -> repeat(perm.card.manaBonus) { p.pool += any } }
        repeat(p.treasures) { p.pool += any or TREASURE }
    }

    /** Plays the land that best fixes the colours the hand needs, preferring one that enters untapped. */
    private fun playLand(p: Seat) {
        playOneLand(p)
        if (seats.any { it.alive && it.has { s -> s.extraLand } }) playOneLand(p)
    }

    private fun playOneLand(p: Seat) {
        val lands = p.hand.filter { it.isLand }
        if (lands.isEmpty()) return
        val have = p.allSources().fold(0) { m, s -> m or s.card.produces }
        val need = (p.hand.filter { !it.isLand } + p.commandZone).fold(0) { m, c -> m or c.cost.colorsNeeded } and ManaColor.ALL
        val pick = lands.maxByOrNull { l ->
            val newColors = Integer.bitCount(l.produces and need and have.inv())
            newColors * 10 + Integer.bitCount(l.produces and need) * 2 + (if (l.entersTapped) 0 else 3)
        }!!
        p.hand.remove(pick)
        p.lands += Perm(pick, sick = false).also { it.tapped = pick.entersTapped }
        p.track.landsPlayed++
        p.track.landByTurn[turnIdx] = true
        noteCast(p, pick)
        fire(p, Trigger.LANDFALL)
    }

    private fun opponents(p: Seat) = seats.filter { it.alive && it !== p }
    private fun threat(q: Seat) = q.boardPower * 2 + q.nonLands().count { it.card.abilities.isNotEmpty() || it.card.anthem > 0 } * 3 +
        q.rocks.size + q.life / 4
    private fun biggestThreat(p: Seat) = opponents(p).maxByOrNull { threat(it) }

    /** Instants held for opponents' turns: counterspells, instant removal and protection. */
    private fun isHeld(c: SimCard) = c.type == CardType.INSTANT &&
        (c.roles - Role.OTHER).let { r -> r.isNotEmpty() && r.all { it == Role.COUNTER || it == Role.PROTECTION || it == Role.REMOVAL || it == Role.TRICK } }

    /** A spell's cost after "spells cost {N} less" effects. */
    private fun costOf(p: Seat, c: SimCard, base: ManaCost = c.cost): ManaCost {
        if (c.statics.freeWithCommander && p.creatures.any { it.card.isCommander }) return ManaCost.FREE
        var off = 0
        for (perm in p.nonLands()) {
            val r = perm.card
            if (r.reduceBy == 0) continue
            val f = r.reduceFor.orEmpty()
            val ok = when {
                f.isBlank() -> true
                f == "creature" -> c.isCreature
                f == "noncreature" -> !c.isCreature
                f == "artifact" -> c.isArtifact
                f == "enchantment" -> c.type == CardType.ENCHANTMENT
                // "Spells you cast from anywhere other than your hand": the commander, cast from the command zone.
                f == "commander" -> c.isCommander
                else -> c.type == CardType.INSTANT || c.type == CardType.SORCERY
            }
            if (ok) off += r.reduceBy
        }
        off += selfReduction(p, c)
        return if (off == 0) base else base.copy(generic = (base.generic - off).coerceAtLeast(0))
    }

    /** Convoke, improvise, delve, affinity, undaunted and "costs {1} less for each …". */
    private fun selfReduction(p: Seat, c: SimCard): Int = when (c.cast.selfReduce) {
        null -> 0
        SelfReduce.CONVOKE -> p.creatures.count { !it.tapped }
        SelfReduce.IMPROVISE -> p.others.count { it.card.isArtifact } + p.creatures.count { it.card.isArtifact && !it.tapped } + p.foods + p.clues
        SelfReduce.DELVE -> p.graveyard.size
        SelfReduce.AFFINITY -> p.nonLands().count { it.card.isArtifact } + p.treasures + p.foods + p.clues
        SelfReduce.UNDAUNTED -> opponents(p).size
        SelfReduce.PER_SCALE -> c.cast.reducePer * (c.cast.reduceScale?.let { count(p, it) } ?: 0)
    }

    /** What convoke and delve actually use up: creatures tapped, graveyard cards exiled. */
    private fun spendReduction(p: Seat, c: SimCard) {
        val used = minOf(selfReduction(p, c), c.cost.generic)
        if (used <= 0) return
        when (c.cast.selfReduce) {
            SelfReduce.CONVOKE -> p.creatures.filter { !it.tapped }.sortedBy { (if (it.sick) 0 else 100) + it.power }.take(used).forEach { it.tapped = true }
            SelfReduce.DELVE -> repeat(used) { p.graveyard.minByOrNull { if (it.isCreature) 1 else 0 }?.let { p.graveyard.remove(it) } }
            else -> Unit
        }
    }

    /** Pays for [c] (with X as large as the spare mana allows) and records X for its effects. */
    private fun pay(p: Seat, c: SimCard, cost: ManaCost, reserve: Int): Boolean {
        if (cost.x == 0) { castX = 0; return payCost(cost, p.pool, commit = true) }
        val spare = (p.pool.size - cost.total - reserve).coerceIn(0, 12)
        if (spare < 1) return false
        castX = spare
        return payCost(cost.copy(generic = cost.generic + spare), p.pool, commit = true)
    }

    private fun castSpells(p: Seat) {
        if (tryCombo(p)) return
        val held = p.hand.filter { isHeld(it) }.minByOrNull { it.mv }
        val reserve = if (held != null && rng.nextFloat() < 0.7f) held.cost.total else 0
        while (p.alive) {
            val cmd = p.commandZone.firstOrNull()
            if (cmd != null && (p.creatures.size < 3 || rng.nextFloat() < 0.5f)) {
                val cost = costOf(p, cmd, withTax(p, cmd))
                if (cost.total + (if (cost.x > 0) 1 else 0) <= p.pool.size && pay(p, cmd, cost, 0)) {
                    p.commandZone.remove(cmd)
                    p.track.manaByTurn[turnIdx] += cost.total
                    p.cmdCasts[cmd.name] = (p.cmdCasts[cmd.name] ?: 0) + 1
                    p.track.cmdCasts++
                    if (p.track.cmdFirstCast < 0) p.track.cmdFirstCast = turnIdx + 1
                    noteCast(p, cmd)
                    castTriggers(p, cmd)
                    if (!tryCounter(p, cmd)) resolve(p, cmd) else p.commandZone += cmd
                    continue
                }
            }
            val playable = p.hand.filter { c ->
                val cost = costOf(p, c)
                !c.isLand && cost.total + (if (cost.x > 0) 1 else 0) <= p.pool.size - (if (c === held) 0 else reserve) && wantsToCast(p, c) &&
                    payCost(cost, p.pool, commit = false)
            }
            val pick = bestPlay(p, playable, reserve, held) ?: break
            val res = if (pick === held) 0 else reserve
            // Overload when there's mana for it (Vandalblast, Cyclonic Rift).
            val over = pick.cast.overload?.takeIf { o -> p.pool.size - o.total >= res && payCost(o, p.pool, commit = false) }
            if (over != null) { payCost(over, p.pool, commit = true); castX = 0 }
            else {
                spendReduction(p, pick)
                if (!pay(p, pick, costOf(p, pick), res)) break
            }
            p.hand.remove(pick)
            payExtraCost(p, pick)
            p.track.manaByTurn[turnIdx] += pick.cost.total
            noteCast(p, pick)
            castTriggers(p, pick)
            castWithOptions(p, pick, res, overload = over != null)
            castX = 0
            if (tryCombo(p)) return
        }
        if (!p.alive) return
        evokeAndRecast(p, reserve)
    }

    /** Kicker, buyback and copies are paid if there's mana to spare; then the spell resolves (unless countered). */
    private fun castWithOptions(p: Seat, c: SimCard, reserve: Int, overload: Boolean = false, fromGraveyard: Boolean = false) {
        val o = c.cast
        kicks = 0
        o.kicker?.let { k -> while (kicks < (if (o.multikicker) 5 else 1) && p.pool.size - k.total >= reserve && payCost(k, p.pool, commit = true)) kicks++ }
        val buyback = o.buyback?.let { b -> p.pool.size - b.total >= reserve && payCost(b, p.pool, commit = true) } == true
        if (tryCounter(p, c)) { if (!c.isPermanent || !fromGraveyard) p.graveyard += c; kicks = 0; return }
        overloaded = overload
        resolve(p, c)
        // Demonstrate and storm: the spell's effects again (storm: once per earlier spell this turn).
        val copies = (if (o.demonstrate) 1 else 0) + (if (o.storm) (p.spellsThisTurn - 1).coerceIn(0, 6) else 0)
        if (!c.isPermanent) repeat(copies) { if (p.alive) fireCard(p, c, null, Trigger.CAST) }
        overloaded = false
        // Squad, offspring, replicate: token copies (or spell copies) for each extra payment.
        o.copyCost?.let { cc ->
            var made = 0
            while (made < o.copyMax && p.alive && p.pool.size - cc.total >= reserve && payCost(cc, p.pool, commit = true)) {
                made++
                if (c.isPermanent) { enter(p, Perm(c.copiedBy(c, token = true), sick = !c.haste)); p.track.tokens++ } else fireCard(p, c, null, Trigger.CAST)
            }
        }
        kicks = 0
        if (!c.isPermanent) {
            when {
                buyback && p.graveyard.remove(c) -> p.hand += c
                o.rebound && !fromGraveyard && p.graveyard.remove(c) -> p.rebounds += c
                fromGraveyard && (o.gyMode == GyMode.FLASHBACK || o.gyMode == GyMode.JUMP_START || o.gyMode == GyMode.ESCAPE) -> p.graveyard.remove(c)
            }
        }
    }

    /**
     * After the main casting: evoke a creature for its enters effect when its full cost is out of reach, then cast one
     * card from the graveyard (flashback, retrace, unearth, embalm…) with the mana left.
     */
    private fun evokeAndRecast(p: Seat, reserve: Int) {
        // Suspend: cards that can't be (or have no cost to be) cast normally go on a timer.
        for (c in p.hand.filter { it.cast.suspend != null }.toList()) {
            val sc = c.cast.suspend!!
            if ((c.cost.total > 0 && payCost(costOf(p, c), p.pool, commit = false)) || !wantsToCast(p, c)) continue
            if (p.pool.size - sc.total < reserve || !payCost(sc, p.pool, commit = true)) continue
            p.hand.remove(c)
            p.suspended += c to c.cast.suspendTurns.coerceIn(1, 6)
        }
        for (c in p.hand.filter { it.cast.evoke != null }.toList()) {
            val ev = c.cast.evoke!!
            if (payCost(costOf(p, c), p.pool, commit = false) || !wantsToCast(p, c)) continue
            if (c.abilities.none { it.trigger == Trigger.ETB } && (c.roles - Role.CREATURE - Role.OTHER).isEmpty()) continue
            if (p.pool.size - ev.total < reserve || !payCost(ev, p.pool, commit = true)) continue
            p.hand.remove(c)
            noteCast(p, c)
            castTriggers(p, c)
            if (tryCounter(p, c)) { p.graveyard += c; continue }
            resolve(p, c)
            p.creatures.lastOrNull { it.card === c }?.let { die(p, it, null, c, sacrificed = true) }
            if (!p.alive) return
        }
        for (c in p.graveyard.filter { it.cast.gyCost != null }.toList()) {
            val o = c.cast
            val gc = o.gyCost ?: continue
            if (!p.alive) return
            if (!c.isLand && !wantsToCast(p, c)) continue
            when (o.gyMode) {
                GyMode.RETRACE -> if (p.hand.none { it.isLand } || p.lands.size + p.hand.count { it.isLand } < 5) continue
                GyMode.JUMP_START -> if (p.hand.isEmpty()) continue
                GyMode.ESCAPE -> if (p.graveyard.size < 5) continue
                else -> Unit
            }
            if (p.pool.size - gc.total < reserve || !payCost(gc, p.pool, commit = true)) continue
            p.graveyard.remove(c)
            when (o.gyMode) {
                GyMode.RETRACE -> p.hand.firstOrNull { it.isLand }?.let { p.hand.remove(it); p.graveyard += it }
                GyMode.JUMP_START -> discardWorst(p, 1, null)
                GyMode.ESCAPE -> repeat(3) { p.graveyard.minByOrNull { it.mv }?.let { x -> if (x !== c) p.graveyard.remove(x) } }
                else -> Unit
            }
            noteCast(p, c)
            castTriggers(p, c)
            p.track.manaByTurn[turnIdx] += gc.total
            when (o.gyMode) {
                GyMode.UNEARTH, GyMode.ENCORE -> {
                    val perm = if (o.gyMode == GyMode.ENCORE) Perm(c.copiedBy(c, token = true), sick = false) else Perm(c, sick = false)
                    enter(p, perm.also { it.exileAtEnd = true })
                }
                GyMode.EMBALM -> { enter(p, Perm(c.copiedBy(c, token = true), sick = !c.haste)); p.track.tokens++ }
                GyMode.ETERNALIZE -> { enter(p, Perm(c.copiedBy(c, token = true, 4, 4, "zombie"), sick = !c.haste)); p.track.tokens++ }
                else -> castWithOptions(p, c, reserve, fromGraveyard = true)
            }
            break
        }
    }

    /**
     * Of the spells that can be cast now, picks the next one from the set that uses this turn's mana best (up to six
     * candidates are tried in every combination), so two 3-drops beat one 4-drop when there are 6 mana.
     */
    private fun bestPlay(p: Seat, playable: List<SimCard>, reserve: Int, held: SimCard?): SimCard? {
        if (playable.size <= 1) return playable.firstOrNull()
        val cands = playable.sortedByDescending { priority(it) }.take(6)
        var best = cands.first()
        var bestScore = Int.MIN_VALUE
        for (mask in 1 until (1 shl cands.size)) {
            val trial = p.pool.toMutableList()
            var score = 0
            var ok = true
            var first: SimCard? = null
            for (i in cands.indices) {
                if (mask and (1 shl i) == 0) continue
                val c = cands[i]
                val cost = costOf(p, c).let { if (it.x > 0) it.copy(generic = it.generic + 1) else it }
                if (!payCost(cost, trial, commit = true) || (c !== held && trial.size < reserve)) { ok = false; break }
                score += priority(c) + 5 * cost.total
                if (first == null) first = c
            }
            if (ok && score > bestScore) { bestScore = score; best = first!! }
        }
        return best
    }

    private fun castTriggers(p: Seat, c: SimCard) {
        p.spellsThisTurn++
        if (!c.isPermanent) p.lastSpell = c
        fire(p, Trigger.CAST_SPELL)
        if (!c.isCreature) fire(p, Trigger.CAST_NONCREATURE)
        for (q in seats) if (q.alive) { if (q !== p) fire(q, Trigger.OPP_CAST); fire(q, Trigger.ANY_CAST) }
        repeat(c.cascade) { if (p.alive) cascade(p, c.mv) }
    }

    /** Cascade: the first nonland card with lower mana value from the top is cast for free; the rest go to the bottom. */
    private fun cascade(p: Seat, mv: Int) {
        val revealed = mutableListOf<SimCard>()
        while (p.library.isNotEmpty()) {
            val top = p.library.removeFirst()
            if (!top.isLand && top.mv < mv) {
                p.library.addAll(revealed.shuffled(rng))
                noteCast(p, top)
                val x = castX
                castX = 0
                resolve(p, top)
                castX = x
                return
            }
            revealed += top
        }
        p.library.addAll(revealed)
    }

    private fun priority(c: SimCard): Int = when {
        c.isManaSource || Role.RAMP_LAND in c.roles -> 100 - c.mv
        Role.WIPE in c.roles || Role.REMOVAL in c.roles -> 60 + c.mv
        Role.ENGINE in c.roles || Role.DRAW in c.roles || Role.TUTOR in c.roles || c.abilities.isNotEmpty() -> 50 + c.mv
        else -> 40 + c.mv
    }

    /** Combo pieces that only work when cast at combo time (spells, "you win" creatures) stay in hand until then. */
    private fun isHeldComboPiece(p: Seat, c: SimCard): Boolean = p.deck.combos.isNotEmpty() &&
        (!c.isPermanent || c.abilities.any { a -> a.effects.any { it is WinGame } }) &&
        p.deck.combos.any { combo -> c.name.substringBefore(" // ").lowercase() in combo.keys }

    private fun wantsToCast(p: Seat, c: SimCard): Boolean = when {
        isHeldComboPiece(p, c) -> false
        !canPayExtra(p, c) -> false
        // Fogs wait for an attack.
        !c.isPermanent && c.abilities.isNotEmpty() && c.abilities.all { a -> a.effects.all { it == Fog } } -> false
        // One-sided sweepers: whenever they kill something; edicts and discard: when an opponent has something to lose.
        Role.WIPE in c.roles && c.parses(Sweep::class.java) && c.abilities.any { a -> a.effects.any { it is Sweep && it.oppOnly } } ->
            opponents(p).any { it.creatures.isNotEmpty() }
        // Counterspells, protection and combat tricks wait for the right moment.
        isHeld(c) && Role.REMOVAL !in c.roles -> false
        Role.WIPE in c.roles && !c.isCreature -> opponents(p).sumOf { it.boardPower } >= p.boardPower + 6
        // Instant removal is kept for an attacker unless there's a big threat now.
        isHeld(c) -> opponents(p).any { q -> q.creatures.any { it.power >= 4 || it.card.isCommander } || q.others.any { it.card.abilities.isNotEmpty() } }
        Role.REMOVAL in c.roles && !c.isCreature && c.roles.none { it == Role.DRAW || it == Role.TOKENS } ->
            opponents(p).any { q -> targets(q, c, p).isNotEmpty() }
        Role.RECURSION in c.roles && !c.isCreature -> p.graveyard.any { it.isCreature }
        else -> true
    }

    /** An opponent holding a counterspell and the open mana for it may counter a meaningful spell. */
    private fun tryCounter(caster: Seat, spell: SimCard): Boolean {
        if (spell.mv < 3 || spell.uncounterable) return false
        for (q in opponents(caster)) {
            val counter = q.hand.firstOrNull { Role.COUNTER in it.roles && (it.roles - Role.OTHER - Role.COUNTER).isEmpty() } ?: continue
            if (!payCost(counter.cost, q.open, commit = false)) continue
            if (rng.nextFloat() < 0.6f) {
                payCost(counter.cost, q.open, commit = true)
                q.hand.remove(counter); q.graveyard += counter
                noteCast(q, counter)
                q.track.counters++
                q.track.card(counter.name).removals++
                caster.track.card(spell.name).countered++
                return true
            }
        }
        return false
    }

    private fun resolve(p: Seat, c: SimCard) {
        val r = c.roles
        if (c.isCreature || (c.isPermanent && c.type != CardType.UNKNOWN)) {
            enter(p, Perm(c, sick = !c.haste))
        } else {
            fireCard(p, c, null, Trigger.CAST)
        }
        if (!p.alive) return
        // Role-based effects stand in only where the text wasn't read into an effect of that kind.
        if (Role.RAMP_LAND in r && !c.parses(FetchLands::class.java)) rampLand(p)
        if (Role.TUTOR in r) c.tutor?.let { tutor(p, it) } ?: if (Role.DRAW !in r) draw(p, 1, counted = false) else Unit
        if (Role.WIPE in r) { if (!c.parses(Sweep::class.java)) wipe(p, c) }
        else if (Role.REMOVAL in r && !c.parses(Remove::class.java)) removeOne(p, c)
        if (Role.RECURSION in r && !c.parses(Reanimate::class.java)) recur(p, c)
        // Roles found from tags but not read from the text keep a simple version of the effect.
        if (Role.DRAW in r && !c.parses(Draw::class.java)) draw(p, c.draws.coerceAtLeast(1))
        if ((Role.BURN in r || Role.DRAIN in r) && !c.parses(Damage::class.java) && !c.parses(LoseLife::class.java)) {
            if (Role.DRAIN in r) {
                for (q in opponents(p)) damagePlayer(p, q, c.damage.coerceAtLeast(1), c, combat = false, cause = EndCondition.LIFE_LOSS)
            } else if (Role.REMOVAL !in r) {
                biggestThreat(p)?.let { damagePlayer(p, it, c.damage.coerceAtLeast(2), c, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE) }
            }
        }
        if (Role.TOKENS in r && !c.parses(MakeTokens::class.java) && c.tokenPower > 0) {
            makeTokens(p, c, MakeTokens(1, TokenKind.CREATURE, "Creature", c.tokenPower, c.tokenPower))
        }
        if (Role.LIFEGAIN in r && !c.parses(GainLife::class.java) && !c.parses(LoseLife::class.java)) gainLife(p, 3)
        if (!c.isPermanent) p.graveyard += c
    }

    // ───────────────────────────── Permanents, triggers and effects ─────────────────────────────

    private fun enter(p: Seat, perm: Perm) {
        val c = perm.card
        if (c.echo != null && !c.isToken) perm.echoDue = true
        when {
            c.isCreature -> p.creatures += perm
            Role.ROCK in c.roles -> {
                p.rocks += perm
                if (c.produces != 0 && c.manaActivation == 0) repeat(((c.manaScale?.let { count(p, it) } ?: 1) * c.manaAmount).coerceIn(1, 30)) { p.pool += c.produces }
            }
            else -> p.others += perm
        }
        // Auras go straight onto the best creature; equipment waits to be equipped.
        if (c.isAura && (c.buff > 0 || c.grants != 0)) {
            val host = p.creatures.filter { it !== perm && !it.card.isManaSource }.maxByOrNull { it.power + it.toughness }
            if (host != null) perm.attachedTo = host else { p.others.remove(perm); p.graveyard += c; return }
        }
        if (c.isCreature) for (o in p.nonLands()) {
            val st = o.card.statics
            if (st.etbCounters > 0 && o !== perm && isTribe(p, perm, st.etbCountersTribe)) addCounters(p, perm, st.etbCounters)
        }
        if (c.anthem > 0 || perm.attachedTo != null || c.teamGrants != 0 || c.isCreature || c.lordGrants != 0) recalcBonus(p)
        fireCard(p, c, perm, Trigger.ETB)
        // A saga's first chapter and a planeswalker's first activation happen right away.
        if (c.chapters.isNotEmpty()) { perm.chapter = 1; apply(p, c, perm, c.chapters[0]) }
        if (c.isPlaneswalker && c.loyalty.isNotEmpty()) {
            val up = c.loyalty.maxByOrNull { it.delta }!!
            if (perm.loyalty + up.delta >= 1) { perm.loyalty += up.delta; apply(p, c, perm, up.effects) }
        }
        if (c.isCreature) fire(p, Trigger.CREATURE_ETB, subject = perm)
        if (c.isArtifact) fire(p, Trigger.ARTIFACT_ETB, exclude = perm)
        if (c.type == CardType.ENCHANTMENT) fire(p, Trigger.ENCHANTMENT_ETB)
        if (c.isCommander) fire(p, Trigger.COMMANDER, subject = perm)
        if (c.isCreature) for (q in seats) if (q !== p && q.alive) fire(q, Trigger.OPP_CREATURE_ETB, subject = perm)
        if (c.isCreature && seats.any { it !== p && it.alive && it.has { s -> s.oppCreaturesEnterTapped } }) perm.tapped = true
        // Curses go on the opponent who's most in the way.
        if (c.cursesPlayer) perm.cursed = biggestThreat(p)
    }

    /** Whether [c] is of creature type [tribe] ("chosen" = the deck's main type, "token" = any token). */
    private fun isTribe(p: Seat, c: Perm, tribe: String?): Boolean = when (tribe) {
        null -> true
        "token" -> c.token
        "nontoken" -> !c.token
        "artifact" -> c.card.isArtifact
        "flying" -> c.flying
        "countered" -> c.counters > 0 || c.kwCounters != 0
        "legendary" -> c.card.isCommander || "legendary" in c.card.subtypes
        "chosen" -> (p.deck.mainTribe != null && p.deck.mainTribe in c.card.subtypes) || allTypes(p, c)
        else -> tribe in c.card.subtypes || allTypes(p, c)
    }

    /** Maskwood Nexus: your creatures are every creature type. */
    private fun allTypes(p: Seat, c: Perm) = c.card.isCreature && p.has { it.allTypes } && (c in p.creatures)

    private fun lordApplies(p: Seat, lord: Perm, c: Perm): Boolean {
        val lt = lord.card.lordType ?: return true
        return (lt == "token" || lord !== c) && isTribe(p, c, lt)
    }

    private fun bonusFor(p: Seat, c: Perm): Int = p.nonLands().sumOf { a ->
        val attached = if (a.attachedTo === c) a.card.buff else 0
        attached + if (a.card.anthem != 0 && lordApplies(p, a, c)) a.card.anthem else 0
    }

    private fun recalcBonus(p: Seat) {
        val nl = p.nonLands()
        val attachments = nl.filter { it.attachedTo != null }
        val anthems = nl.filter { it.card.anthem != 0 }
        val teamSources = nl.filter { it.card.teamGrants != 0 }
        val lordSources = nl.filter { it.card.lordGrants != 0 || it.card.lordLandwalk != 0 }
        val countersGrant = nl.fold(0) { m, a -> m or a.card.statics.countersGrant }
        for (cr in p.creatures) {
            var b = 0
            var att = 0
            for (a in attachments) if (a.attachedTo === cr) { b += a.card.buff; att = att or a.card.grants }
            for (a in anthems) if (lordApplies(p, a, cr)) b += a.card.anthem
            cr.bonus = b
            var team = 0
            for (a in teamSources) if (!(a === cr && a.card.teamGrantsOthers)) team = team or a.card.teamGrants
            var lordKw = 0
            var walk = 0
            for (a in lordSources) if (lordApplies(p, a, cr)) { lordKw = lordKw or a.card.lordGrants; walk = walk or a.card.lordLandwalk }
            cr.granted = team or lordKw or att or (if (cr.counters > 0) countersGrant else 0)
            cr.grantedWalk = walk
            // "Power and toughness equal to the number of …".
            cr.card.ptScale?.let { sc -> scaleSource = cr; cr.dyn = count(p, sc) }
        }
    }

    /** How many of something [p] has, for "for each" effects. */
    private fun count(p: Seat, s: Scale): Int = when (s.kind) {
        ScaleKind.CREATURES -> p.creatures.size
        ScaleKind.ARTIFACTS -> p.nonLands().count { it.card.isArtifact } + p.treasures + p.foods + p.clues
        ScaleKind.ENCHANTMENTS -> p.others.count { it.card.type == CardType.ENCHANTMENT }
        ScaleKind.LANDS -> p.lands.size
        ScaleKind.LANDS_OF_COLOR -> p.lands.count { it.card.produces and s.color != 0 }
        ScaleKind.TRIBE -> p.creatures.count { c -> c.card.subtypes.any { it == s.tribe } }
        ScaleKind.OPPONENTS -> opponents(p).size
        ScaleKind.HAND -> p.hand.size
        ScaleKind.DEVOTION -> p.nonLands().sumOf { perm -> perm.card.cost.pips.count { it and s.color != 0 } }
        ScaleKind.TOKENS -> p.creatures.count { it.token } + p.treasures + p.foods + p.clues
        ScaleKind.GREATEST_POWER -> p.creatures.maxOfOrNull { it.power } ?: 0
        ScaleKind.OPP_CREATURES -> opponents(p).sumOf { it.creatures.size }
        ScaleKind.ALL_CREATURES -> seats.filter { it.alive }.sumOf { it.creatures.size }
        ScaleKind.GRAVEYARD -> p.graveyard.size
        ScaleKind.GRAVEYARD_CREATURES -> p.graveyard.count { it.isCreature }
        ScaleKind.KICKED -> kicks
        ScaleKind.PERMANENTS -> p.permanents().size
        ScaleKind.COUNTERS -> scaleSource?.counters ?: 0
        ScaleKind.SOURCE_POWER -> scaleSource?.power ?: 2
        ScaleKind.SPELL_MV -> p.lastSpell?.mv ?: 2
        ScaleKind.GREATEST_MV -> p.nonLands().maxOfOrNull { it.card.mv } ?: 0
    }

    /** The permanent whose ability is resolving, for "for each counter on it". */
    private var scaleSource: Perm? = null

    /** An effect's amount: X is the X paid (or 2), "for each" multiplies. */
    private fun amount(p: Seat, n: Int, scale: Scale?, src: Perm? = null): Int {
        val base = when (n) { XN -> if (castX > 0) castX else 2; DMG -> lastDamage; LIFE_GAINED -> p.lifeGainedTurn; POWER -> src?.power ?: 2; LAST_GAIN -> lastGain; else -> n }
        return if (scale == null) base else (if (n == XN) 1 else base) * count(p, scale)
    }

    /** Fires [trigger] on every permanent [p] controls (or only [only]), plus eminence abilities in the command zone. */
    private fun fire(p: Seat, trigger: Trigger, only: Perm? = null, exclude: Perm? = null, subject: Perm? = null) {
        if (!p.alive) return
        val bit = 1L shl trigger.ordinal
        if (only != null) { if (only.card.triggerMask and bit != 0L) fireCard(p, only.card, only, trigger, subject); return }
        // Most triggers concern no permanent at all: check before copying the board.
        if (p.creatures.any { it.card.triggerMask and bit != 0L } || p.others.any { it.card.triggerMask and bit != 0L } ||
            p.rocks.any { it.card.triggerMask and bit != 0L }) {
            for (perm in p.nonLands()) {
                if (perm === exclude || perm.card.triggerMask and bit == 0L) continue
                fireCard(p, perm.card, perm, trigger, subject)
                if (!p.alive || p.triggerBudget <= 0) return
            }
        }
        if (p.commandZone.any { it.triggerMask and bit != 0L }) for (c in p.commandZone.toList()) fireCard(p, c, null, trigger, subject, commandZone = true)
    }

    private fun fireCard(p: Seat, c: SimCard, perm: Perm?, trigger: Trigger, subject: Perm? = null, commandZone: Boolean = false) {
        c.abilities.forEachIndexed { i, a ->
            if (a.trigger != trigger || p.triggerBudget <= 0 || !p.alive) return@forEachIndexed
            if (commandZone && !a.fromCommandZone) return@forEachIndexed
            // "Whenever another Cat you control attacks": the attacker must be that type, and not the source itself.
            if (a.tribe != null && (subject == null || !isTribe(p, subject, a.tribe))) return@forEachIndexed
            if ((trigger == Trigger.CREATURE_ATTACKS || trigger == Trigger.CREATURE_ETB) && subject != null && subject === perm) return@forEachIndexed
            if (a.kicked && kicks == 0) return@forEachIndexed
            if (a.minLevel > 1 && (perm == null || perm.level < a.minLevel || (trigger == Trigger.LEVEL_UP && perm.level != a.minLevel))) return@forEachIndexed
            if (a.minPower > 0 && (subject == null || subject.power < a.minPower)) return@forEachIndexed
            if (a.maxPower > 0 && (subject == null || subject.power > a.maxPower)) return@forEachIndexed
            if (a.evolve && (subject == null || perm == null || (subject.power <= perm.power && subject.toughness <= perm.toughness))) return@forEachIndexed
            if (a.oncePerTurn && perm != null && !p.usedOnce.add(perm to i)) return@forEachIndexed
            a.optionalCost?.let { cost ->
                val mana = if (active === p) p.pool else p.open
                if (!payCost(cost, mana, commit = true)) return@forEachIndexed
            }
            p.triggerBudget--
            apply(p, c, perm, a.effects, subject)
        }
    }

    private fun apply(p: Seat, src: SimCard, perm: Perm?, effects: List<Effect>, subject: Perm? = null) {
        scaleSource = perm
        val powerOf = subject ?: perm
        for (e in effects) {
            if (!p.alive) return
            when (e) {
                is Pump -> {
                    val targets = when (e.target) {
                        PumpTarget.SELF -> listOfNotNull(perm?.takeIf { it in p.creatures })
                        PumpTarget.TRIGGERING -> listOfNotNull(subject?.takeIf { it in p.creatures })
                        PumpTarget.ALL -> p.creatures.filter { isTribe(p, it, e.tribe) }
                        PumpTarget.ANOTHER -> listOfNotNull(p.creatures.filter { it !== perm && isTribe(p, it, e.tribe) }
                            .maxByOrNull { (if (!it.sick && !it.tapped) 100 else 0) + it.power })
                    }
                    val x = if (e.power == XN) amount(p, XN, e.scale, powerOf) else e.power
                    targets.forEach { t ->
                        if (e.doublePower) t.temp += t.power else t.temp += x
                        t.tempKw = t.tempKw or e.keywords
                    }
                }
                is Explore -> {
                    val who = when (e.who) {
                        ExploreWho.SELF -> listOfNotNull(perm?.takeIf { it in p.creatures })
                        ExploreWho.TRIGGERING -> listOfNotNull(subject?.takeIf { it in p.creatures })
                        ExploreWho.TRIBE -> p.creatures.filter { isTribe(p, it, e.tribe) }
                        ExploreWho.ALL -> p.creatures.toList()
                    }
                    val times = if (p.nonLands().any { it.card.exploreTwice }) 2 else 1
                    who.forEach { c -> repeat(times) { explore(p, c) } }
                }
                is LandFromHand -> {
                    val land = p.hand.filter { it.isLand }.maxByOrNull { Integer.bitCount(it.produces) }
                    if (land != null) {
                        p.hand.remove(land)
                        p.lands += Perm(land, sick = false).also { it.tapped = land.entersTapped }
                        if (!land.entersTapped && active === p) p.pool += (land.produces.takeIf { it != 0 } ?: ManaColor.C)
                        fire(p, Trigger.LANDFALL)
                    } else if (e.elseDraw) draw(p, 1)
                }
                TopOfLibrary -> if (rng.nextBoolean()) draw(p, 1)
                ReturnToHand -> if (p.graveyard.remove(src)) p.hand += src
                is GraveyardCopy -> {
                    val pick = seats.flatMap { q -> q.graveyard.filter { it.isCreature }.map { q to it } }.maxByOrNull { (_, c) -> c.mv * 2 + c.power + c.abilities.size * 2 }
                    if (pick != null) {
                        pick.first.graveyard.remove(pick.second)
                        enter(p, Perm(pick.second.copiedBy(src, token = true, e.power, e.toughness, e.tribe), sick = !pick.second.haste))
                    }
                }
                StealFromGraveyard -> {
                    val pick = opponents(p).flatMap { q -> q.graveyard.filter { it.isCreature || it.isArtifact }.map { q to it } }
                        .maxByOrNull { (_, c) -> c.mv * 2 + c.power }
                    if (pick != null) { pick.first.graveyard.remove(pick.second); enter(p, Perm(pick.second, sick = !pick.second.haste)) }
                }
                is Earthbend -> {
                    val land = p.lands.filter { it.card.earthbentFrom == null }.minByOrNull { Integer.bitCount(it.card.produces) }
                    if (land != null) {
                        p.lands.remove(land)
                        enter(p, Perm(land.card.earthbent(), sick = false).also { it.tapped = land.tapped })
                        p.creatures.lastOrNull()?.let { addCounters(p, it, amount(p, e.n, null)) }
                    }
                }
                is MakeTokens -> makeTokens(p, src, e.copy(count = amount(p, e.count, e.scale).coerceAtMost(20)))
                is Draw -> if (e.eachPlayer) seats.filter { it.alive }.forEach { draw(it, amount(p, e.n, e.scale).coerceAtMost(10)) }
                    else draw(p, amount(p, e.n, e.scale).coerceAtMost(10))
                is GainLife -> gainLife(p, amount(p, e.n, e.scale))
                is LoseLife -> {
                    val k = amount(p, e.n, e.scale)
                    val hit = if (e.each) opponents(p) else listOfNotNull(opponents(p).minByOrNull { it.life })
                    hit.forEach { damagePlayer(p, it, k, src, combat = false, cause = EndCondition.LIFE_LOSS) }
                    if (e.gainBack) gainLife(p, k * hit.size)
                }
                is CopyCreature -> {
                    val pool = if (e.ownOnly) p.creatures.map { p to it } else seats.filter { it.alive }.flatMap { q -> q.creatures.map { q to it } }
                    val best = pool.filter { (_, c) -> c !== perm && !c.card.isToken }.maxByOrNull { (_, c) -> value(c) }?.second
                    if (best != null) {
                        if (e.token) enter(p, Perm(best.card.copiedBy(src, token = true), sick = !best.card.haste))
                        else if (perm != null && perm in p.creatures) {
                            // The clone becomes a copy: swap it for a copy of the chosen creature.
                            p.creatures.remove(perm)
                            enter(p, Perm(best.card.copiedBy(src, token = false), sick = perm.sick))
                        }
                    }
                }
                is Damage -> (if (e.each) opponents(p) else listOfNotNull(opponents(p).minByOrNull { it.life })).forEach {
                    damagePlayer(p, it, amount(p, e.n, e.scale, powerOf), src, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE)
                }
                is Counters -> {
                    val k = amount(p, e.n, e.scale, powerOf)
                    when {
                        k < 0 -> if (perm != null && perm in p.creatures) perm.counters += k
                        // "Distribute that many counters": spread over your best creatures.
                        e.n == LIFE_GAINED -> p.creatures.filter { it !== perm }.sortedByDescending { it.power }.take(k).forEach { addCounters(p, it, 1) }
                        e.all -> p.creatures.filter { isTribe(p, it, e.tribe) }.forEach { addCounters(p, it, k) }
                        e.spread > 1 -> p.creatures.sortedByDescending { it.power }.take(e.spread).forEach { addCounters(p, it, k) }
                        e.self && perm != null && perm.card.isCreature -> addCounters(p, perm, k)
                        else -> p.creatures.maxByOrNull { it.power }?.let { addCounters(p, it, k) }
                    }
                }
                is Mill -> {
                    val k = amount(p, e.n, null)
                    when (e.target) {
                        MillTarget.DAMAGED -> lastDamaged?.let { mill(it, k) }
                        MillTarget.SELF -> mill(p, k)
                        MillTarget.EACH_OPPONENT -> opponents(p).forEach { mill(it, k) }
                        MillTarget.OPPONENT -> opponents(p).minByOrNull { it.library.size }?.let { mill(it, k) }
                    }
                }
                is Poison -> (if (e.each) opponents(p) else listOfNotNull(opponents(p).maxByOrNull { it.poison })).forEach {
                    poison(p, it, amount(p, e.n, null), src)
                }
                Proliferate -> {
                    opponents(p).filter { it.poison > 0 }.forEach { poison(p, it, 1, src) }
                    p.creatures.filter { it.counters > 0 }.forEach { it.counters++ }
                    p.nonLands().filter { it.card.isPlaneswalker }.forEach { it.loyalty++ }
                }
                ExtraTurn -> if (p.extraTurns < 1) p.extraTurns++
                ExtraCombat -> if (p.extraCombats < 2) p.extraCombats++
                is WinGame -> when (e.condition) {
                    WinCondition.LIFE -> if (p.life >= e.n) win(p, src, "${src.name}|${p.index}")
                    WinCondition.ORACLE -> {
                        val devotion = p.nonLands().sumOf { pm -> pm.card.cost.pips.count { it and ManaColor.U != 0 } }
                        if (p.library.size <= devotion) win(p, src, "${src.name}|${p.index}")
                    }
                    WinCondition.EMPTY_LIBRARY -> Unit // checked when drawing
                }
                is Remove -> if (overloaded) {
                    for (q in opponents(p)) for (v in targets(q, src, p, e.kind, e.damage.coerceAtLeast(0), e.what)) removeOne(p, src, v, q, e.kind, e.damage, e.what)
                } else repeat(e.count.coerceIn(1, 6)) { if (p.alive) removeTarget(p, src, e, powerOf) }
                is Sweep -> sweep(p, src, amount(p, e.n, e.scale, powerOf), e)
                is Edict -> whoSeats(p, e.who).forEach { q -> repeat(amount(p, e.n, null).coerceIn(1, 5)) { sacrificeWorst(q, e.what, p, src) } }
                is Discard -> if (e.who == Who.SELF) discardWorst(p, e.n.coerceAtMost(p.hand.size), null)
                    else whoSeats(p, e.who).forEach { q -> discardWorst(q, amount(p, e.n, null).coerceAtMost(q.hand.size), if (q === p) null else p) }
                is Scry -> scry(p, amount(p, e.n, null), e.surveil)
                is TapCreatures -> opponents(p).flatMap { q -> q.creatures.filter { !it.tapped } }
                    .sortedByDescending { it.power + it.toughness }.take(amount(p, e.n, null)).forEach { it.tapped = true }
                Monarch -> monarch = p
                Venture -> if (rng.nextBoolean()) draw(p, 1) else gainLife(p, 2)
                is FreeCast -> freeCast(p, e)
                is Wheel -> {
                    val alive = seats.filter { it.alive }
                    val most = alive.maxOf { it.hand.size }
                    for (q in alive) {
                        val gone = q.hand.toList()
                        q.graveyard += gone; q.hand.clear(); q.track.discarded += gone.size
                        gone.forEach { lostACard(q, it) }
                    }
                    for (q in alive) if (q.alive) draw(q, if (e.n == 0) most else e.n)
                }
                is AddMana -> {
                    val any = (p.deck.colors and ManaColor.ALL).let { if (it == 0) ManaColor.ALL else it }
                    repeat(amount(p, e.n, null).coerceAtMost(10)) { (if (active === p) p.pool else p.open) += any }
                }
                is Steal -> steal(p, e)
                is Reanimate -> {
                    val back = p.graveyard.filter { if (e.anyPermanent) it.isPermanent && !it.isLand else it.isCreature }
                        .maxByOrNull { it.mv * 2 + it.power + it.abilities.size * 2 }
                    if (back != null) {
                        p.graveyard.remove(back)
                        if (e.toBattlefield) enter(p, Perm(back, sick = !back.haste)) else p.hand += back
                    }
                }
                is FetchLands -> { repeat(e.toBattlefield) { rampLand(p) }; repeat(e.toHand) { landToHand(p) } }
                SacrificeSelf -> if (perm != null) die(p, perm, null, src, sacrificed = true)
                is Germ -> {
                    val germ = SimCard.token(MakeTokens(1, TokenKind.CREATURE, e.name, e.power, e.toughness), src)
                    val gp = Perm(germ, sick = true)
                    enter(p, gp)
                    if (perm != null && perm.card.isEquipment && gp in p.creatures) { perm.attachedTo = gp; recalcBonus(p) }
                    p.track.tokens++
                }
                Myriad -> if (powerOf != null && powerOf in p.creatures) for (q in opponents(p)) {
                    if (q === defending || !q.alive) continue
                    if (q.creatures.none { b -> !b.tapped && !b.has(Kw.CANT_BLOCK) && (!powerOf.flying || b.flying || b.reach) }) hitPlayer(p, powerOf, q, powerOf.power)
                }
                is Devour -> if (perm != null) {
                    val food = p.creatures.filter { it.token && it.power <= 1 && it !== perm }.take(6)
                    food.forEach { die(p, it, null, src, sacrificed = true) }
                    addCounters(p, perm, food.size * e.n)
                }
                TakeThatCard -> {
                    val c = lostCard
                    val from = lostFrom
                    if (c != null && from != null && from !== p && c.isPermanent && from.graveyard.remove(c)) {
                        lostCard = null
                        if (c.isLand) p.lands += Perm(c, sick = false).also { it.tapped = true } else enter(p, Perm(c, sick = !c.haste))
                    }
                }
                Populate -> p.creatures.filter { it.token }.maxByOrNull { it.power + it.toughness }?.let { t ->
                    enter(p, Perm(t.card, sick = !t.card.haste)); p.track.tokens++
                }
                CopySelf -> if (!src.isToken) { enter(p, Perm(src.copiedBy(src, token = true), sick = !src.haste)); p.track.tokens++ }
                Fog -> Unit // used from hand when attacked (see combat)
                Energy, ExileEnds -> Unit
                is ActiveDraws -> active?.let { if (it.alive) draw(it, e.n) }
                Attach -> if (perm != null && perm.card.isEquipment) {
                    (subject?.takeIf { it in p.creatures } ?: p.creatures.filter { !it.card.isManaSource }.maxByOrNull { it.power + it.toughness })?.let { perm.attachedTo = it; recalcBonus(p) }
                }
                DoubleCounters -> { p.creatures.forEach { it.counters = (it.counters * 2).coerceAtMost(60) }; p.nonLands().filter { it.card.isPlaneswalker }.forEach { it.loyalty *= 2 } }
                CopySpell -> p.lastSpell?.let { sp -> if (sp !== src && freeDepth < 3) { freeDepth++; fireCard(p, sp, null, Trigger.CAST); freeDepth-- } }
                is TopCard -> p.library.firstOrNull()?.let { top ->
                    val hit = when (e.match) { "land" -> top.isLand; "creature" -> top.isCreature; else -> !top.isLand }
                    when {
                        hit && e.toBattlefield -> {
                            p.library.removeFirst()
                            if (top.isLand) { p.lands += Perm(top, sick = false).also { it.tapped = true }; fire(p, Trigger.LANDFALL) } else enter(p, Perm(top, sick = !top.haste))
                        }
                        hit || e.elseHand -> draw(p, 1, counted = false)
                    }
                }
                HalfLife -> (lastDamaged?.takeIf { it.alive && it !== p } ?: biggestThreat(p))?.let { q ->
                    damagePlayer(p, q, (q.life + 1) / 2, src, combat = false, cause = EndCondition.LIFE_LOSS)
                }
                is CopyPermanent -> p.nonLands().filter { isKind(it, e.what) && !it.token }.maxByOrNull { it.card.mv * 2 + it.card.manaAmount + it.power }?.let { best ->
                    enter(p, Perm(best.card.copiedBy(src, token = true), sick = !best.card.haste)); p.track.tokens++
                }
                is ClassLevel -> if (perm != null && perm.level == e.n - 1) { perm.level = e.n; fireCard(p, src, perm, Trigger.LEVEL_UP) }
                is Untap -> when (e.what) {
                    "land" -> {
                        val any = (p.deck.colors and ManaColor.ALL).let { if (it == 0) ManaColor.ALL else it }
                        repeat(minOf(e.n, p.lands.size)) { (if (active === p) p.pool else p.open) += any }
                    }
                    "self" -> perm?.tapped = false
                    "creature" -> p.creatures.forEach { it.tapped = false }
                    "target" -> p.creatures.filter { it.tapped }.maxByOrNull { it.power }?.tapped = false
                    else -> p.nonLands().forEach { it.tapped = false }
                }
                is Mark -> if (perm != null) perm.marks += amount(p, e.n, null)
                is MassReanimate -> {
                    val alive = seats.filter { it.alive }
                    if (!e.noncreature) for (q in alive) for (v in q.creatures.toList()) die(q, v, if (q !== p) p else null, src, sacrificed = true)
                    for (q in alive) {
                        val back = q.graveyard.filter { if (e.noncreature) (it.isArtifact || it.type == CardType.ENCHANTMENT) && !it.isCreature else it.isCreature }
                        q.graveyard.removeAll(back.toSet())
                        back.forEach { if (q.alive) enter(q, Perm(it, sick = !it.haste)) }
                    }
                }
                is GraveCast -> if (freeDepth < 3) {
                    seats.filter { it.alive }.flatMap { q -> q.graveyard.filter { !it.isPermanent && it.mv <= e.maxMv }.map { q to it } }
                        .maxByOrNull { (_, c) -> c.mv + priority(c) / 10 }?.let { (q, c) ->
                            q.graveyard.remove(c)
                            freeDepth++
                            noteCast(p, c); resolve(p, c)
                            p.graveyard.remove(c) // exiled instead of going to a graveyard
                            freeDepth--
                        }
                }
                GraveHate -> opponents(p).flatMap { q -> q.graveyard.filter { it.isCreature || it.isPermanent }.map { q to it } }
                    .maxByOrNull { (_, c) -> c.mv * 2 + c.power + c.abilities.size }?.let { (q, c) -> q.graveyard.remove(c) }
                is KeywordCounter -> (if (e.self) perm?.takeIf { it in p.creatures } else p.creatures.filter { !it.card.isManaSource }.maxByOrNull { it.power + it.toughness })
                    ?.let { it.kwCounters = it.kwCounters or e.keywords }
                is OppTokens -> {
                    val who = if (e.each) opponents(p) else listOfNotNull(opponents(p).minByOrNull { threat(it) })
                    who.forEach { q -> makeTokens(q, src, e.tokens) }
                }
                is LoseLifeSelf -> loseLifeSelf(p, amount(p, e.n, null), src)
                is Minor -> Unit
                is ReturnDied -> {
                    val died = (subject ?: perm)?.card
                    val from = seats.firstOrNull { died != null && it.graveyard.contains(died) }
                    if (died != null && from != null && !died.isToken && from.graveyard.remove(died)) {
                        val back = Perm(died, sick = !died.haste).also { it.kwCounters = e.keywords }
                        enter(p, back)
                    }
                }
                Blink -> p.creatures.filter { it !== perm && !it.token && it.card.abilities.any { a -> a.trigger == Trigger.ETB } }
                    .maxByOrNull { it.card.abilities.count { a -> a.trigger == Trigger.ETB } * 10 + it.card.mv }
                    ?.let { b -> if (freeDepth < 3) { freeDepth++; fireCard(p, b.card, b, Trigger.ETB); freeDepth-- } }
                Vanish -> if (perm != null) { if (perm.marks > 1) perm.marks-- else die(p, perm, null, src, sacrificed = true) }
                is Monstrosity -> if (perm != null && !perm.monstrous && perm in p.creatures) {
                    perm.monstrous = true
                    addCounters(p, perm, amount(p, e.n, null))
                    fireCard(p, src, perm, Trigger.MONSTROUS)
                }
            }
        }
    }

    /** Life [p] loses to its own cards (drawbacks): not damage, and nobody is credited with the kill. */
    private fun loseLifeSelf(p: Seat, k: Int, card: SimCard) {
        if (k <= 0 || !p.alive) return
        p.life -= k
        p.track.taken += k
        p.track.takenNonCombat += k
        if (p.life <= 0) eliminate(p, EndCondition.LIFE_LOSS, "${card.name}|${p.index}")
    }

    private fun mill(q: Seat, k: Int) {
        repeat(k.coerceAtMost(q.library.size)) { q.graveyard += q.library.removeFirst() }
        q.track.milled += k
    }

    private fun poison(src: Seat, dst: Seat, k: Int, card: SimCard) {
        if (!dst.alive || k <= 0) return
        dst.poison += k
        if (dst.poison >= 10) {
            src.track.card(card.name).killShots++
            src.track.kills++
            eliminate(dst, EndCondition.POISON, "${card.name}|${src.index}")
        }
    }

    /** [p] wins on the spot (alternate win or a combo): everyone else is out. */
    private fun win(p: Seat, card: SimCard, by: String) {
        val ct = p.track.card(card.name)
        for (q in opponents(p)) {
            ct.killShots++
            p.track.kills++
            eliminate(q, EndCondition.ALT_WIN, by)
        }
    }

    private fun gainLife(p: Seat, k: Int) {
        if (k <= 0 || seats.any { it.alive && it.has { s -> s.noLifeGain } }) return
        val n = (k + p.nonLands().sumOf { it.card.statics.lifeGainPlus }) * (if (p.has { it.lifeGainDouble }) 2 else 1)
        p.life += n; p.track.lifeGained += n; p.lifeGainedTurn += n
        lastGain = n
        fire(p, Trigger.LIFEGAIN)
    }

    /** +1/+1 counters, with Hardened Scales (+1) and doublers (×2) applied. */
    private fun addCounters(p: Seat, c: Perm, k: Int) {
        if (k <= 0) return
        var n = k
        p.nonLands().forEach { n += it.card.counterPlus }
        p.nonLands().forEach { n *= it.card.counterMultiplier }
        c.counters += n.coerceAtMost(40)
        fire(p, Trigger.COUNTERS_PUT, subject = c)
    }

    /** Explore: a land goes to hand; otherwise the creature gets a +1/+1 counter (the card stays on top or is binned). */
    private fun explore(p: Seat, c: Perm) {
        val top = p.library.firstOrNull() ?: return
        if (top.isLand) {
            draw(p, 1)
        } else {
            addCounters(p, c, 1)
            if (top.mv > p.lands.size + 2) p.graveyard += p.library.removeFirst()
        }
        if (p.alive) fire(p, Trigger.EXPLORES, subject = c)
    }

    private fun makeTokens(p: Seat, src: SimCard, t: MakeTokens) {
        when (t.kind) {
            TokenKind.CREATURE -> {
                val card = SimCard.token(t, src)
                val n = (t.count * tokenMultiplier(p)).coerceAtMost(20)
                repeat(n) { if (p.alive) enter(p, Perm(card, sick = !card.haste)) }
                p.track.tokens += n
                squirrels(p, n)
            }
            TokenKind.TREASURE, TokenKind.FOOD, TokenKind.CLUE -> {
                val n = (t.count * tokenMultiplier(p)).coerceAtMost(20)
                if (p.has { it.tripleArtifactTokens }) { p.treasures += n; p.foods += n; p.clues += n }
                else when (t.kind) { TokenKind.TREASURE -> p.treasures += n; TokenKind.FOOD -> p.foods += n; else -> p.clues += n }
                p.track.tokens += n
                fire(p, Trigger.ARTIFACT_ETB)
                squirrels(p, n)
            }
        }
    }

    private fun tokenMultiplier(p: Seat): Int =
        p.nonLands().fold(1) { m, perm -> m * perm.card.tokenMultiplier }.coerceAtMost(8)

    /** Chatterfang: every batch of tokens brings that many 1/1 Squirrels. */
    private fun squirrels(p: Seat, n: Int) {
        val chatter = p.nonLands().firstOrNull { it.card.extraSquirrels } ?: return
        val squirrel = SimCard.token(MakeTokens(1, TokenKind.CREATURE, "Squirrel", 1, 1), chatter.card)
        repeat(n.coerceAtMost(10)) { if (p.alive) { p.creatures += Perm(squirrel, sick = true).also { it.bonus = bonusFor(p, it) } } }
        p.track.tokens += n
    }

    /** After combat: activated abilities, Clues and Food with the mana that's left (keeping any held-up mana). */
    private fun spendLeftover(p: Seat) {
        val reserve = p.hand.filter { isHeld(it) }.minByOrNull { it.mv }?.cost?.total ?: 0
        for (perm in p.nonLands()) {
            for (a in perm.card.activated) {
                if (a.effects.all { it is Pump }) continue
                var uses = if (a.tap || a.sacrificeSelf) 1 else 2
                while (uses-- > 0 && p.alive) {
                    if (a.tap && (perm.tapped || (perm.card.isCreature && perm.sick))) break
                    if (perm !in p.creatures && perm !in p.others && perm !in p.rocks) break
                    if (a.removeCounter && perm.counters < 1) break
                    if (a.removeMarks > 0 && perm.marks < a.removeMarks) break
                    if (a.lifeCost > 0 && p.life <= a.lifeCost + 10) break
                    if (a.discardCost && p.hand.none { it.isLand }) break
                    // Sacrifice outlets only eat small tokens (or spare Food and Clues), never real cards.
                    val artifactFodder = a.sacrificeTribe in setOf("artifact", "food", "clue", "treasure")
                    if (a.sacrificeTribe == "land" || (artifactFodder && p.foods + p.clues == 0)) break
                    val tribe = a.sacrificeTribe?.takeIf { it != "permanent" && it != "nonland" }
                    val fodder = if (a.sacrificeCreature && !artifactFodder) p.creatures.filter { it.token && it.power <= 2 && it !== perm && isTribe(p, it, tribe) }
                        .minByOrNull { it.power } ?: break else null
                    val tappers = if (a.tapCreatures > 0) p.creatures.filter { !it.tapped && it !== perm && isTribe(p, it, a.tapTribe) }
                        .sortedBy { it.power }.take(a.tapCreatures).takeIf { it.size == a.tapCreatures } ?: break else emptyList()
                    if (p.pool.size - a.cost.total < reserve || !payCost(a.cost, p.pool, commit = true)) break
                    if (a.tap) perm.tapped = true
                    if (a.removeCounter) perm.counters--
                    perm.marks -= a.removeMarks
                    if (a.lifeCost > 0) p.life -= a.lifeCost
                    if (a.discardCost) p.hand.firstOrNull { it.isLand }?.let { p.hand.remove(it); p.graveyard += it; p.track.discarded++ }
                    if (artifactFodder) { if (p.foods > 0) p.foods-- else p.clues--; fire(p, Trigger.SACRIFICE) }
                    if (fodder != null) { p.track.sacrificed++; die(p, fodder, null, perm.card, sacrificed = true) }
                    tappers.forEach { it.tapped = true }
                    apply(p, perm.card, perm, a.effects)
                    if (a.sacrificeSelf) { die(p, perm, null, perm.card, sacrificed = true); break }
                }
            }
        }
        // Cycling: cards that won't be cast soon (or spare lands when flooded); landcycling while short on lands.
        for (c in p.hand.filter { it.cast.cycleCost != null }.toList()) {
            val cc = c.cast.cycleCost!!
            val needLand = p.lands.size + p.hand.count { it.isLand } < 5
            val dead = if (c.cast.cycleForLand) needLand || c.mv > p.lands.size + 3
                else (c.isLand && p.lands.size >= 6) || (!c.isLand && c.mv > p.lands.size + 3)
            if (!dead || p.pool.size - cc.total < reserve || !payCost(cc, p.pool, commit = true)) continue
            p.hand.remove(c); p.graveyard += c; p.track.discarded++
            lostACard(p, c)
            fireCard(p, c, null, Trigger.CYCLED)
            fire(p, Trigger.DISCARD)
            if (c.cast.cycleForLand) landToHand(p) else draw(p, 1)
            if (!p.alive) return
        }
        // Abilities used from the graveyard ("{2}{B}, exile ~ from your graveyard: …").
        for (c in p.graveyard.toList()) for (a in c.activated) {
            if (!a.fromGraveyard || a.effects.isEmpty() || !p.alive) continue
            if (p.pool.size - a.cost.total < reserve || !payCost(a.cost, p.pool, commit = true)) continue
            // "Return ~ from your graveyard to your hand" moves it; "exile ~ from your graveyard: …" uses it up.
            if (a.effects.any { it == ReturnToHand }) { p.graveyard.remove(c); p.hand += c; break }
            p.graveyard.remove(c)
            apply(p, c, Perm(c, sick = true), a.effects.filter { it != ReturnToHand })
            break
        }
        val two = ManaCost(emptyList(), 2)
        while (p.clues > 0 && p.pool.size - 2 >= reserve && payCost(two, p.pool, commit = true)) { p.clues--; draw(p, 1) }
        while (p.foods > 0 && p.life < 30 && p.pool.size - 2 >= reserve && payCost(two, p.pool, commit = true)) { p.foods--; gainLife(p, 3) }
    }

    /** Puts a land from the library onto the battlefield tapped, choosing one that adds a needed colour. */
    private fun rampLand(p: Seat) {
        val have = p.allSources().fold(0) { m, s -> m or s.card.produces }
        val need = p.deck.colors and have.inv()
        val land = p.library.filter { it.isLand }
            .maxByOrNull { Integer.bitCount(it.produces and need) * 10 + Integer.bitCount(it.produces) } ?: return
        p.library.remove(land)
        p.lands += Perm(land, sick = false).also { it.tapped = true }
        fire(p, Trigger.LANDFALL)
    }

    /**
     * Finds the card the game state calls for: a missing combo piece, mana when short on lands, an answer when
     * opponents are ahead, otherwise the strongest card castable soon.
     */
    private fun tutor(p: Seat, spec: TutorSpec) {
        val options = p.library.filter { spec.allows(it) }
        if (options.isEmpty()) return
        val have = (p.hand + p.nonLands().map { it.card } + p.commandZone).map { it.name.lowercase() }.toSet()
        // Combo pieces first: the combo closest to complete, any piece this tutor can find.
        val comboPiece = p.deck.combos.map { c -> c.keys.filter { it !in have } }
            .filter { it.isNotEmpty() }
            .sortedBy { it.size }
            .firstNotNullOfOrNull { missing -> options.firstOrNull { it.name.substringBefore(" // ").lowercase() in missing } }
        val mana = p.lands.size + p.rocks.size
        val behind = opponents(p).maxOfOrNull { it.boardPower } ?: 0
        fun value(c: SimCard): Int = c.abilities.size * 4 + c.activated.size * 2 + c.anthem * 5 + c.power +
            (if (Role.ENGINE in c.roles || Role.DRAW in c.roles) 5 else 0) + c.mv - (if (c.mv > mana + 2) 20 else 0)
        val pick = comboPiece
            ?: options.filter { it.isCreature }.takeIf { spec.dest == TutorDest.GRAVEYARD }?.maxByOrNull { it.mv * 2 + it.power + it.abilities.size * 3 }
            ?: options.filter { it.isManaSource || Role.RAMP_LAND in it.roles }.takeIf { mana < 4 }?.minByOrNull { it.mv }
            ?: options.filter { Role.WIPE in it.roles }.takeIf { behind >= p.boardPower + 8 }?.firstOrNull()
            ?: options.filter { Role.REMOVAL in it.roles }.takeIf { behind >= p.boardPower + 4 }?.minByOrNull { it.mv }
            ?: options.maxByOrNull { value(it) }
            ?: return
        p.library.remove(pick)
        val shuffled = p.library.shuffled(rng); p.library.clear(); p.library.addAll(shuffled)
        when (spec.dest) {
            TutorDest.HAND -> p.hand += pick
            TutorDest.TOP -> p.library.addFirst(pick)
            TutorDest.BATTLEFIELD -> if (pick.isPermanent) enter(p, Perm(pick, sick = !pick.haste)) else p.hand += pick
            TutorDest.GRAVEYARD -> p.graveyard += pick
        }
    }

    /** If every piece of a known combo is in play or castable now, cast the rest and win (unless countered). */
    private fun tryCombo(p: Seat): Boolean {
        if (p.deck.combos.isEmpty()) return false
        val inPlay = p.nonLands().map { it.card.name.substringBefore(" // ").lowercase() }.toSet()
        for (combo in p.deck.combos) {
            val missing = combo.keys.filter { it !in inPlay }
            val fromHand = missing.mapNotNull { k ->
                (p.hand + p.commandZone).firstOrNull { it.name.substringBefore(" // ").lowercase() == k }
            }
            if (fromHand.size != missing.size) continue
            if (combo.needsRockMana > 0 && p.rocks.sumOf { it.card.manaAmount } < combo.needsRockMana) continue
            val trial = p.pool.toMutableList()
            val affordable = fromHand.all { c -> payCost(costOf(p, c, if (c in p.commandZone) withTax(p, c) else c.cost), trial, commit = true) } &&
                trial.size >= combo.extraMana
            if (!affordable) continue
            for (c in fromHand) {
                payCost(costOf(p, c, if (c in p.commandZone) withTax(p, c) else c.cost), p.pool, commit = true)
                p.hand.remove(c); p.commandZone.remove(c)
                noteCast(p, c)
                if (tryCounter(p, c)) { p.graveyard += c; return false }
                if (c.isPermanent) enter(p, Perm(c, sick = !c.haste)) else p.graveyard += c
                if (!p.alive) return true
            }
            val last = fromHand.lastOrNull() ?: p.nonLands().first { it.card.name.substringBefore(" // ").lowercase() == combo.keys.last() }.card
            win(p, last, "${combo.name}|${p.index}")
            return true
        }
        return false
    }

    /** Upkeep: planeswalkers use a loyalty ability, sagas move to their next chapter. */
    private fun sagasAndWalkers(p: Seat) {
        for (perm in p.nonLands()) {
            val c = perm.card
            if (c.isPlaneswalker && c.loyalty.isNotEmpty()) {
                // Plus abilities build loyalty; the biggest minus fires when there's loyalty to spare.
                val ult = c.loyalty.filter { it.delta < 0 && perm.loyalty + it.delta > 0 }.minByOrNull { it.delta }
                val use = ult?.takeIf { perm.loyalty + it.delta >= 1 && rng.nextFloat() < 0.5f } ?: c.loyalty.maxByOrNull { it.delta }!!
                if (perm.loyalty + use.delta >= 0) {
                    perm.loyalty += use.delta
                    apply(p, c, perm, use.effects)
                    if (perm.loyalty <= 0) leave(p, perm, null, c, toGraveyard = true)
                }
            }
            if (c.chapters.isNotEmpty() && perm.chapter in 1 until c.chapters.size) {
                perm.chapter++
                apply(p, c, perm, c.chapters[perm.chapter - 1])
                if (perm.chapter >= c.chapters.size) leave(p, perm, null, c, toGraveyard = true)
            }
            if (!p.alive) return
        }
    }

    /** Before combat: equipment without a creature moves onto the best one, if the equip cost can be paid. */
    private fun equipAll(p: Seat) {
        var changed = false
        for (e in (p.others + p.rocks).filter { it.card.isEquipment }) {
            if (e.attachedTo != null && e.attachedTo in p.creatures) continue
            val host = p.creatures.filter { !it.card.isManaSource }.maxByOrNull { it.power + it.toughness } ?: break
            if (!payCost(ManaCost(emptyList(), e.card.equipCost ?: 0), p.pool, commit = true)) continue
            e.attachedTo = host
            changed = true
        }
        if (changed) recalcBonus(p)
    }

    private fun recur(p: Seat, c: SimCard) {
        val back = p.graveyard.filter { it.isPermanent && !it.isLand }
            .maxByOrNull { (if (c.recursionToBattlefield) it.mv * 2 else it.mv) + it.power + it.abilities.size * 2 } ?: return
        p.graveyard.remove(back)
        if (c.recursionToBattlefield) enter(p, Perm(back, sick = !back.haste)) else p.hand += back
    }

    // ───────────────────────────── Removal ─────────────────────────────

    /** Whether [v] is the kind of permanent [what] names ("creature", "artifact or enchantment", "nonland permanent"…). */
    private fun isKind(v: Perm, what: String): Boolean {
        if ("permanent" in what && "non" !in what.substringBefore("permanent")) return true
        val c = v.card
        val ok = ("creature" in what && c.isCreature) || ("artifact" in what && c.isArtifact) ||
            ("enchantment" in what && c.type == CardType.ENCHANTMENT) || ("planeswalker" in what && c.isPlaneswalker) ||
            ("nonland" in what) || ("noncreature" in what && !c.isCreature)
        return ok && !("noncreature" in what && c.isCreature) && !("nonartifact" in what && c.isArtifact) && !("nontoken" in what && v.token)
    }

    /** What removal of [kind] can target among [q]'s permanents. */
    private fun targets(q: Seat, c: SimCard, from: Seat, kind: RemovalKind = c.removalKind, dmg: Int = c.removalDamage, what: String = c.removalWhat): List<Perm> {
        val fighter = if (kind == RemovalKind.FIGHT) from.creatures.maxByOrNull { it.power } else null
        return q.nonLands().filter { v ->
            !v.has(Kw.HEXPROOF) && isKind(v, what) &&
                !(v.has(Kw.INDESTRUCTIBLE) && (kind == RemovalKind.DESTROY || kind == RemovalKind.DAMAGE || kind == RemovalKind.FIGHT)) &&
                when (kind) {
                    RemovalKind.DAMAGE -> v.card.isCreature && v.toughness <= dmg.coerceAtLeast(2)
                    RemovalKind.MINUS -> v.card.isCreature && v.toughness <= dmg.coerceAtLeast(1)
                    RemovalKind.FIGHT -> v.card.isCreature && fighter != null && (fighter.power >= v.toughness || fighter.deathtouch)
                    else -> true
                }
        }
    }

    /** Removal from an ability or spell effect. "Any target" damage goes at a creature worth killing, else a player. */
    private fun removeTarget(p: Seat, src: SimCard, e: Remove, powerOf: Perm?) {
        val dmg = if (e.damage == POWER) powerOf?.power ?: 0 else if (e.damage == XN) amount(p, XN, null) else e.damage
        if (e.kind == RemovalKind.DAMAGE && dmg <= 0) return
        if (e.what == "any") {
            val lethal = opponents(p).firstOrNull { it.life <= dmg }
            val creature = opponents(p).flatMap { q -> targets(q, src, p, RemovalKind.DAMAGE, dmg, "creature").map { q to it } }
                .maxByOrNull { (q, v) -> value(v) + threat(q) / 4 }
            if (lethal == null && creature != null && value(creature.second) >= 5) {
                removeOne(p, src, creature.second, creature.first, RemovalKind.DAMAGE, dmg, "creature")
            } else (lethal ?: biggestThreat(p))?.let { damagePlayer(p, it, dmg, src, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE) }
            return
        }
        removeOne(p, src, kind = e.kind, dmg = dmg, what = e.what)
    }

    /** Damage (or -N/-N) to every creature: each one it's enough for dies. */
    private fun sweep(p: Seat, src: SimCard, k: Int, e: Sweep) {
        if (k <= 0) return
        var others = 0
        var total = 0
        for (q in seats.filter { it.alive }) {
            if (e.oppOnly && q === p) continue
            for (v in q.creatures.toList()) {
                if ((e.flyersSafe && v.flying) || v.has(Kw.INDESTRUCTIBLE) || v.toughness > k) continue
                total++
                if (q !== p) { others++; p.track.removalAtSeat[q.index]++ }
                die(q, v, if (q !== p) p else null, src)
            }
        }
        if (e.players) {
            for (q in opponents(p)) damagePlayer(p, q, k, src, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE)
            if (p.alive && !e.oppOnly) { p.life -= k; p.track.taken += k; if (p.life <= 0) eliminate(p, EndCondition.NONCOMBAT_DAMAGE, "${src.name}|${p.index}") }
        }
        p.track.removalSpells++
        p.track.card(src.name).removals += others
        if (total >= 3 && others >= 2) p.track.wipes++
    }

    /** The players an effect hits. */
    private fun whoSeats(p: Seat, who: Who): List<Seat> = when (who) {
        Who.EACH_OPPONENT -> opponents(p)
        Who.EACH_PLAYER -> seats.filter { it.alive }
        Who.SELF -> listOf(p)
        Who.DEFENDER -> listOfNotNull(defending?.takeIf { it.alive && it !== p } ?: biggestThreat(p))
        Who.OPPONENT -> listOfNotNull(biggestThreat(p))
    }

    /** [q] sacrifices its least valuable [what] (tokens first). */
    private fun sacrificeWorst(q: Seat, what: String, by: Seat, src: SimCard) {
        if (!q.alive) return
        if (what == "land") {
            val land = q.lands.minByOrNull { Integer.bitCount(it.card.produces) } ?: return
            q.lands.remove(land); q.graveyard += land.card
            lostACard(q, land.card)
            return
        }
        val victim = q.nonLands().filter { isKind(it, what) }.minByOrNull { (if (it.token) 0 else 100) + value(it) } ?: return
        if (q !== by) { by.track.removalAtSeat[q.index]++; by.track.card(src.name).removals++ }
        die(q, victim, if (q !== by) by else null, src, sacrificed = true)
    }

    /** [q] discards its [k] least useful cards: spare lands when they have plenty, else what they can't cast soon. */
    private fun discardWorst(q: Seat, k: Int, by: Seat?) {
        repeat(k) {
            if (q.hand.isEmpty()) return
            val lands = q.lands.size + q.hand.count { it.isLand }
            val worst = (if (lands >= 6) q.hand.firstOrNull { it.isLand } else null)
                ?: q.hand.filter { !it.isLand }.maxByOrNull { it.mv - (if (it.mv <= q.lands.size + 1) 10 else 0) }
                ?: q.hand.first()
            q.hand.remove(worst); q.graveyard += worst; q.track.discarded++
            lostACard(q, worst)
            fire(q, Trigger.DISCARD)
        }
    }

    /** A card [q] sacrificed or discarded: "whenever an opponent sacrifices or discards" triggers (Tergrid). */
    private fun lostACard(q: Seat, c: SimCard) {
        if (!c.isPermanent || c.isToken) return
        for (o in seats) {
            if (o === q || !o.alive) continue
            lostCard = c; lostFrom = q
            fire(o, Trigger.OPP_LOSES_CARD)
        }
        lostCard = null; lostFrom = null
    }

    /** Scry (bottom) or surveil (graveyard) the top [k]: keep lands while short, spells castable soon. */
    private fun scry(p: Seat, k: Int, surveil: Boolean) {
        val top = (0 until k.coerceAtMost(p.library.size)).map { p.library.removeFirst() }
        val landsAhead = p.lands.size + p.hand.count { it.isLand }
        val (keep, bin) = top.partition { c -> if (c.isLand) landsAhead < 6 else c.mv <= landsAhead + 2 }
        keep.reversed().forEach { p.library.addFirst(it) }
        if (surveil) { p.graveyard += bin; p.track.milled += bin.size; fire(p, Trigger.SURVEIL) } else p.library.addAll(bin)
    }

    /** Casts a card for free: the best one from hand, or (discover, cascade-like) the first fitting card from the top. */
    private fun freeCast(p: Seat, e: FreeCast) {
        if (freeDepth >= 3) return
        if (e.commander) {
            val cmd = p.commandZone.firstOrNull() ?: return
            p.commandZone.remove(cmd)
            p.cmdCasts[cmd.name] = (p.cmdCasts[cmd.name] ?: 0) + 1
            p.track.cmdCasts++
            if (p.track.cmdFirstCast < 0) p.track.cmdFirstCast = turnIdx + 1
            freeDepth++
            noteCast(p, cmd); castTriggers(p, cmd); resolve(p, cmd)
            freeDepth--
            return
        }
        val pick: SimCard = if (e.fromLibrary) {
            val revealed = mutableListOf<SimCard>()
            var found: SimCard? = null
            while (p.library.isNotEmpty() && revealed.size < 20) {
                val top = p.library.removeFirst()
                if (!top.isLand && top.mv <= e.maxMv) { found = top; break }
                revealed += top
            }
            p.library.addAll(revealed.shuffled(rng))
            found ?: return
        } else {
            p.hand.filter { !it.isLand && it.mv <= e.maxMv && wantsToCast(p, it) }.maxByOrNull { it.mv * 10 + priority(it) }?.also { p.hand.remove(it) } ?: return
        }
        freeDepth++
        noteCast(p, pick)
        castTriggers(p, pick)
        val x = castX
        castX = 0
        if (p.alive) resolve(p, pick)
        castX = x
        freeDepth--
    }

    /** Gain control of the best opposing creature (or artifact); Threaten effects give it back at end of turn. */
    private fun steal(p: Seat, e: Steal) {
        val pick = opponents(p).flatMap { q ->
            (if (e.what == "artifact") q.rocks + q.others.filter { it.card.isArtifact } else q.creatures)
                .filter { !it.has(Kw.HEXPROOF) && !it.card.isCommander && it.attachedTo == null }.map { q to it }
        }.maxByOrNull { (_, v) -> value(v) } ?: return
        val (q, v) = pick
        if (!(q.creatures.remove(v) or q.rocks.remove(v) or q.others.remove(v))) return
        q.track.received++
        recalcBonus(q)
        v.tapped = false
        v.sick = !e.untilEndOfTurn && !v.card.haste
        when {
            v.card.isCreature -> p.creatures += v
            Role.ROCK in v.card.roles -> p.rocks += v
            else -> p.others += v
        }
        if (e.untilEndOfTurn) borrowed += v to q
        recalcBonus(p)
    }

    /** Threatened creatures go back to their owners. */
    private fun returnBorrowed(p: Seat) {
        if (borrowed.isEmpty()) return
        for ((v, owner) in borrowed.toList()) {
            if (p.creatures.remove(v) || p.rocks.remove(v) || p.others.remove(v)) {
                if (owner.alive) { if (v.card.isCreature) owner.creatures += v else owner.others += v; recalcBonus(owner) }
            }
        }
        borrowed.clear()
        recalcBonus(p)
    }

    /** A basic land from the library into hand (one that adds a colour the deck is missing, if any). */
    private fun landToHand(p: Seat) {
        val have = p.allSources().fold(0) { m, s -> m or s.card.produces } or p.hand.filter { it.isLand }.fold(0) { m, c -> m or c.produces }
        val land = p.library.filter { it.isLand }.maxByOrNull { Integer.bitCount(it.produces and p.deck.colors and have.inv()) * 10 - Integer.bitCount(it.produces) } ?: return
        p.library.remove(land)
        p.hand += land
    }

    /** Whether the spell's additional cost (discard, sacrifice, life) can be paid without wrecking the board. */
    private fun canPayExtra(p: Seat, c: SimCard): Boolean = when (c.extraCost) {
        null -> true
        ExtraCost.DISCARD -> p.hand.size >= 2
        ExtraCost.SAC_CREATURE -> p.creatures.any { it.token || it.power <= 2 }
        ExtraCost.SAC_ARTIFACT -> p.foods + p.clues > 0 || p.rocks.size >= 3
        ExtraCost.SAC_LAND -> p.lands.size >= 4
        ExtraCost.LIFE -> p.life > 15
    }

    private fun payExtraCost(p: Seat, c: SimCard) {
        when (c.extraCost) {
            null -> Unit
            ExtraCost.DISCARD -> discardWorst(p, 1, null)
            ExtraCost.SAC_CREATURE -> p.creatures.minByOrNull { (if (it.token) 0 else 100) + value(it) }?.let { die(p, it, null, c, sacrificed = true) }
            ExtraCost.SAC_ARTIFACT -> when {
                p.foods > 0 -> p.foods--
                p.clues > 0 -> p.clues--
                else -> p.rocks.minByOrNull { it.card.manaAmount }?.let { die(p, it, null, c, sacrificed = true) }
            }
            ExtraCost.SAC_LAND -> p.lands.minByOrNull { Integer.bitCount(it.card.produces) }?.let { p.lands.remove(it); p.graveyard += it.card }
            ExtraCost.LIFE -> { val k = if (castX > 0) castX else 3; p.life -= k }
        }
    }

    private fun value(v: Perm): Int = v.power * 2 + (if (v.card.isCommander) 6 else 0) + v.card.abilities.size * 3 +
        v.card.anthem * 4 + v.card.mv + (if (v.has(Kw.WARD)) -3 else 0)

    private fun removeOne(p: Seat, c: SimCard, only: Perm? = null, onlyOwner: Seat? = null,
                          kind: RemovalKind = c.removalKind, dmg: Int = c.removalDamage, what: String = c.removalWhat) {
        val choice = if (only != null && onlyOwner != null) onlyOwner to only else
            opponents(p).flatMap { q -> targets(q, c, p, kind, dmg, what).map { q to it } }.maxByOrNull { (q, v) -> value(v) + threat(q) / 4 } ?: return
        val (target, victim) = choice
        p.track.removalSpells++
        p.track.removalAtSeat[target.index]++
        // The owner may answer with a protection spell.
        val save = target.hand.firstOrNull { it.type == CardType.INSTANT && Role.PROTECTION in it.roles && payCost(it.cost, target.open, commit = false) }
        if (save != null && value(victim) >= 6) {
            payCost(save.cost, target.open, commit = true)
            target.hand.remove(save); target.graveyard += save
            noteCast(target, save)
            return
        }
        p.track.card(c.name).removals++
        when (kind) {
            RemovalKind.EXILE -> leave(target, victim, byOpponent = p, by = c, toGraveyard = false)
            RemovalKind.BOUNCE -> {
                leave(target, victim, byOpponent = p, by = c, toGraveyard = false)
                if (!victim.token && !victim.card.isCommander) target.hand += victim.card
            }
            RemovalKind.FIGHT -> {
                val fighter = p.creatures.maxByOrNull { it.power } ?: return
                val hitBack = victim.power
                if (fighter.power >= victim.toughness || fighter.deathtouch) die(target, victim, p, c)
                if ((hitBack >= fighter.toughness || victim.deathtouch) && !fighter.has(Kw.INDESTRUCTIBLE)) die(p, fighter, target, victim.card)
            }
            else -> die(target, victim, p, c)
        }
    }

    private fun wipe(p: Seat, c: SimCard) {
        var others = 0
        var total = 0
        val exile = c.removalKind == RemovalKind.EXILE || c.removalKind == RemovalKind.BOUNCE
        val bounce = c.removalKind == RemovalKind.BOUNCE
        for (q in seats.filter { it.alive }) for (victim in q.creatures.toList()) {
            if (!exile && victim.has(Kw.INDESTRUCTIBLE)) continue
            if (bounce && !victim.token && !victim.card.isCommander) q.hand += victim.card
            total++
            if (q !== p) {
                others++
                p.track.removalAtSeat[q.index]++
                if (exile) leave(q, victim, byOpponent = p, by = c, toGraveyard = false) else die(q, victim, p, c)
            } else {
                p.track.sacrificed++
                if (exile) leave(q, victim, byOpponent = null, by = c, toGraveyard = false) else die(q, victim, null, c)
            }
        }
        p.track.removalSpells++
        p.track.card(c.name).removals += others
        if (total >= 3 && others >= 2) p.track.wipes++
    }

    /** A permanent dies: graveyard (or command zone), then dies triggers everywhere. */
    private fun die(owner: Seat, victim: Perm, byOpponent: Seat?, by: SimCard, sacrificed: Boolean = false) {
        // Regeneration: pay the cost (from the pool on its own turn, open mana otherwise) and it survives, tapped.
        val regen = victim.card.regenCost
        if (!sacrificed && regen != null && victim in owner.creatures && payCost(regen, if (active === owner) owner.pool else owner.open, commit = true)) {
            victim.tapped = true
            return
        }
        val attachments = if (victim.card.isCreature) owner.nonLands().filter { it.attachedTo === victim } else emptyList()
        if (!leave(owner, victim, byOpponent, by, toGraveyard = true)) return
        if (sacrificed) {
            fire(owner, Trigger.SACRIFICE); lostACard(owner, victim.card)
            for (q in seats) if (q.alive) fire(q, Trigger.SACRIFICE_ANY)
        }
        if (victim.card.isCreature) {
            fireCard(owner, victim.card, victim, Trigger.SELF_DIES)
            attachments.forEach { if (owner.alive) fireCard(owner, it.card, it, Trigger.ATTACHED_DIES, subject = victim) }
            fire(owner, Trigger.OWN_DIES, subject = victim)
            for (q in seats) fire(q, Trigger.DIES)
            // Persist and undying: back once, with a -1/-1 or +1/+1 counter.
            val back = victim.card.returnsOnDeath
            if (back != 0 && owner.alive && !victim.token && (if (back < 0) victim.counters >= 0 else victim.counters <= 0) && owner.graveyard.remove(victim.card)) {
                val again = Perm(victim.card, sick = !victim.card.haste)
                enter(owner, again)
                if (back < 0) again.counters -= 1 else addCounters(owner, again, 1)
            }
        }
    }

    /** Removes [victim] from play and records why. Returns false if it was already gone. */
    private fun leave(owner: Seat, victim: Perm, byOpponent: Seat?, by: SimCard, toGraveyard: Boolean): Boolean {
        val removed = owner.creatures.remove(victim) or owner.others.remove(victim) or owner.rocks.remove(victim)
        if (!removed) return false
        if (byOpponent != null) {
            byOpponent.track.permsRemoved++
            byOpponent.track.removedByType.merge(victim.card.type, 1, Int::plus)
            owner.track.received++
            if (!victim.token) {
                val ct = owner.track.card(victim.card.name)
                ct.removed++
                ct.removedBy.merge("${by.name}|${byOpponent.index}", 1, Int::plus)
                owner.track.ownRemoved.merge(victim.card.name, 1, Int::plus)
            }
        } else if (victim.card.isCreature) {
            owner.track.creaturesLost++
        }
        when {
            victim.card.earthbentFrom != null -> owner.lands += Perm(victim.card.earthbentFrom, sick = false).also { it.tapped = true }
            victim.token -> Unit
            victim.card.isCommander -> { if (byOpponent != null) owner.track.cmdRemoved++; owner.commandZone += victim.card }
            toGraveyard -> owner.graveyard += victim.card
        }
        // Auras on a creature that leaves go with it; equipment stays and waits to be re-equipped.
        var attachmentsChanged = victim.attachedTo != null
        if (victim.card.isCreature) {
            for (att in (owner.others + owner.rocks).filter { it.attachedTo === victim }) {
                attachmentsChanged = true
                att.attachedTo = null
                if (att.card.isAura) { owner.others.remove(att); owner.graveyard += att.card }
            }
        }
        if (victim.card.anthem > 0 || victim.card.teamGrants != 0 || attachmentsChanged) recalcBonus(owner)
        return true
    }

    // ───────────────────────────── Damage ─────────────────────────────

    private fun damagePlayer(src: Seat, dst: Seat, dealt: Int, card: SimCard, combat: Boolean, cause: EndCondition) {
        if (!dst.alive || dealt <= 0) return
        var amount = dealt
        if (cause != EndCondition.LIFE_LOSS && src !== dst) {
            if (src.has { it.damageDouble }) amount *= 2
            if (dst.has { it.damageHalved }) amount -= (amount + 1) / 2
            if (amount <= 0) return
        }
        val t = turnIdx
        dst.life -= amount
        val dt = dst.track
        val st = src.track
        dt.taken += amount
        dt.takenByTurn[(dt.turnsTaken - 1).coerceIn(0, MAX_TURNS - 1)] += amount
        dt.takenBySeat[src.index] += amount
        dt.takenByCard.merge("${card.name}|${src.index}", amount, Int::plus)
        st.dmgPlayers += amount
        if (combat) { st.dmgCombat += amount; dt.takenCombat += amount } else { st.dmgNonCombat += amount; dt.takenNonCombat += amount }
        st.dmgByTurn[t] += amount
        st.dmgBySeat[dst.index] += amount
        val ct = st.card(card.name)
        ct.damage += amount
        if (combat) ct.combatDamage += amount else ct.nonCombatDamage += amount
        (ct.dmgBySeat ?: IntArray(n).also { ct.dmgBySeat = it })[dst.index] += amount
        (ct.dmgByRound ?: IntArray(MAX_TURNS).also { ct.dmgByRound = it })[t] += amount
        var why = cause
        // Only the commander itself deals commander damage; its tokens don't.
        if (combat && card.isCommander && !card.isToken) {
            st.dmgCommander += amount
            dt.takenCommander += amount
            dst.cmdDamageFrom[src.index] += amount
            if (dst.cmdDamageFrom[src.index] >= 21) why = EndCondition.COMMANDER_DAMAGE
        }
        if (dst.life <= 0 || why == EndCondition.COMMANDER_DAMAGE) {
            ct.killShots++
            st.kills++
            eliminate(dst, why, "${card.name}|${src.index}")
        }
    }

    // ───────────────────────────── Combat ─────────────────────────────

    /** Lands each player controls, as colours (for landwalk). */
    private fun landColors(q: Seat) = q.lands.fold(0) { m, l -> m or l.card.produces }

    /** Champion of Lambholt and the like on the attacking side: the smallest power that can still block (0 = any). */
    private var minBlockerPower = 0
    private var minBlockerPowerFor: Perm? = null

    private fun canBlock(b: Perm, a: Perm): Boolean =
        !b.tapped && !b.has(Kw.CANT_BLOCK) && !a.unblockable && (!a.flying || b.flying || b.reach) &&
            (minBlockerPower == 0 || b.power >= minBlockerPower || (minBlockerPowerFor != null && a !== minBlockerPowerFor)) &&
            ((a.card.landwalk or a.grantedWalk) and blockingLands == 0) &&
            (!a.has(Kw.FEAR) || b.card.isArtifact || b.card.cost.pips.any { it and ManaColor.B != 0 })

    /** The defending player's land colours during the current combat. */
    private var blockingLands = 0

    private fun evasive(a: Perm, blockers: List<Perm>) =
        blockers.none { canBlock(it, a) } || (a.menace && blockers.count { canBlock(it, a) } < 2)

    private fun combat(p: Seat) {
        seats.forEach { if (it.alive) recalcBonus(it) }
        // Beginning of combat (eminence and the like), before attackers are chosen.
        fire(p, Trigger.COMBAT_START)
        if (!p.alive) return
        // Pump and evasion abilities ("{T}: target Merfolk can't be blocked") are used now, not after combat.
        for (perm in p.nonLands()) for (a in perm.card.activated) {
            if (a.effects.none { it is Pump } || (a.tap && (perm.tapped || (perm.card.isCreature && perm.sick)))) continue
            if (!payCost(a.cost, p.pool, commit = true)) continue
            if (a.tap) perm.tapped = true
            apply(p, perm.card, perm, a.effects)
        }
        val ready = p.creatures.filter {
            !it.sick && !it.tapped && it.power > 0 && !it.has(Kw.DEFENDER) && !(it.card.isManaSource && it.power <= 1) &&
                (it.card.cast.crew == 0 || p.creatures.any { o -> o !== it && o.card.cast.crew == 0 && !o.tapped && o.power >= it.card.cast.crew })
        }
        if (ready.isEmpty()) return
        val opps = opponents(p)
        fun blockersOf(q: Seat) = q.creatures.filter { !it.tapped && !it.has(Kw.CANT_BLOCK) && it.card.cast.crew == 0 }
        // Attack where the most damage gets through (open boards, low life), going for a kill when one is on.
        fun through(q: Seat): Int {
            val bl = blockersOf(q)
            val blockable = ready.filter { !evasive(it, bl) }.sortedByDescending { it.power }
            return ready.filter { evasive(it, bl) }.sumOf { it.power } + blockable.drop(bl.size).sumOf { it.power }
        }
        val defender = opps.firstOrNull { q -> through(q) >= q.life }
            ?: opps.maxByOrNull { q -> through(q) * 4 - q.life + threat(q) / 3 + rng.nextInt(6) }
            ?: return
        blockingLands = landColors(defender)
        defending = defender
        val champ = p.creatures.filter { it.card.statics.smallCantBlock || it.card.statics.smallCantBlockTeam }.maxByOrNull { it.power }
        minBlockerPower = champ?.power ?: 0
        minBlockerPowerFor = champ?.takeIf { !it.card.statics.smallCantBlockTeam }
        val blockers = blockersOf(defender).toMutableList()
        // Keep blockers home when the table could kill us on the crack-back.
        val incoming = opps.maxOf { q -> q.creatures.filter { !it.has(Kw.DEFENDER) }.sumOf { it.power } }
        // (Only at low life, and only one: the AI otherwise stalls boards far more than real tables do.)
        val keepCount = if (incoming >= p.life && p.life <= 12) 1 else 0
        val keep = ready.filter { !it.has(Kw.VIGILANCE) }.sortedByDescending { it.toughness }.take(keepCount).toSet()
        val attackers = (ready - keep).filter { a -> !(a.flying && defender.has { it.flyersCantAttackYou }) }.filter { a ->
            // Don't send a creature into a blocker that kills it and survives, unless it can't be blocked well.
            evasive(a, blockers) || blockers.none { b -> canBlock(b, a) && b.power >= a.toughness && b.toughness > a.power } || rng.nextFloat() < 0.25f
        }.toMutableList()
        // Ghostly Prison: each attacker costs mana; attack with as many as can be paid for, biggest first.
        defender.nonLands().sumOf { it.card.statics.attackTax }.takeIf { it > 0 }?.let { tax ->
            val affordable = p.pool.size / tax
            if (attackers.size > affordable) { attackers.sortByDescending { it.power }; while (attackers.size > affordable) attackers.removeAt(attackers.size - 1) }
            repeat(attackers.size * tax) { if (p.pool.isNotEmpty()) p.pool.removeAt(0) }
        }
        // Vehicles are crewed by a creature that stays home (or they don't attack).
        for (v in attackers.filter { it.card.cast.crew > 0 }) {
            val crew = p.creatures.filter { o -> o !== v && o !in attackers && o.card.cast.crew == 0 && !o.tapped && o.power >= v.card.cast.crew }.minByOrNull { it.power }
            if (crew != null) crew.tapped = true else attackers.remove(v)
        }
        if (attackers.isEmpty()) return
        p.track.attacks++
        p.track.attackersTotal += attackers.size
        // Exalted: a lone attacker gets +1/+1 for each exalted permanent.
        if (attackers.size == 1) p.nonLands().count { it.has(Kw.EXALTED) }.takeIf { it > 0 }?.let { attackers[0].temp += it }
        fire(p, Trigger.ATTACK)
        for (q in seats) if (q.alive) {
            fire(q, Trigger.ANY_ATTACK)
            q.others.filter { it.cursed === defender }.forEach { c -> fireCard(q, c.card, c, Trigger.CURSE) }
        }
        fire(defender, Trigger.OPP_ATTACKS)
        attackers.toList().forEach { a ->
            if (p.alive && a.card.isCommander) fire(p, Trigger.COMMANDER, subject = a)
            if (p.alive) fire(p, Trigger.SELF_ATTACK, only = a)
            if (p.alive) p.nonLands().filter { it.attachedTo === a }.forEach { fireCard(p, it.card, it, Trigger.ATTACHED_ATTACKS, subject = a) }
            if (p.alive) fire(p, Trigger.CREATURE_ATTACKS, subject = a)
        }
        attackers.forEach { if (!it.has(Kw.VIGILANCE)) it.tapped = true }
        attackers.retainAll { it in p.creatures }
        if (!p.alive || !defender.alive) return

        // The defender may kill the biggest attacker with instant removal before blocks.
        defender.hand.firstOrNull { c -> isHeld(c) && Role.REMOVAL in c.roles && payCost(c.cost, defender.open, commit = false) }?.let { c ->
            val legal = targets(p, c, defender)
            val target = attackers.filter { it in legal }.maxByOrNull { it.power }
            if (target != null && target.power >= 3) {
                payCost(c.cost, defender.open, commit = true)
                defender.hand.remove(c); defender.graveyard += c
                noteCast(defender, c)
                removeOne(defender, c, only = target, onlyOwner = p)
                attackers.retainAll { it in p.creatures }
            }
        }

        var incomingNow = attackers.sumOf { it.power * if (it.doubleStrike) 2 else 1 }
        // A Fog from hand when the attack would hurt a lot.
        if (incomingNow >= defender.life || incomingNow >= 10) {
            defender.hand.firstOrNull { c -> !c.isPermanent && c.parses(Fog::class.java) && payCost(c.cost, defender.open, commit = false) }?.let { fog ->
                payCost(fog.cost, defender.open, commit = true)
                defender.hand.remove(fog); defender.graveyard += fog
                noteCast(defender, fog)
                fireCard(defender, fog, null, Trigger.CAST)
                return
            }
        }
        for (a in attackers.sortedByDescending { it.power }) {
            if (!defender.alive || !p.alive) break
            if (a !in p.creatures) continue
            val danger = defender.life <= incomingNow
            val options = blockers.filter { canBlock(it, a) }.let { if (a.menace && it.size < 2) emptyList() else it }
            val blocker = options.filter { b ->
                (b.toughness > a.power && !a.deathtouch) || (b.power >= a.toughness && b.card.mv <= a.card.mv) || danger
            }.maxByOrNull { it.toughness * 2 + it.power }
            val strikes = if (a.doubleStrike) 2 else 1
            incomingNow -= a.power * strikes
            if (blocker != null && (danger || rng.nextFloat() < 0.6f)) {
                blockers.remove(blocker)
                defender.track.blocks++
                fight(p, a, defender, blocker)
            } else {
                val dmg = a.power * strikes
                if (a.lifelink) gainLife(p, dmg)
                hitPlayer(p, a, defender, dmg)
            }
        }
    }

    /** One attacker blocked by one blocker, with first/double strike, deathtouch, trample, lifelink and indestructible. */
    private fun fight(p: Seat, a: Perm, defender: Seat, b: Perm) {
        fun lethal(src: Perm, dst: Perm, dmg: Int) = dmg > 0 && (dmg >= dst.toughness || src.deathtouch) && !dst.has(Kw.INDESTRUCTIBLE) &&
            !dst.card.statics.noCombatDamageTo && !src.card.statics.noCombatDamageBy
        /** (attacker dies, blocker dies) with the current stats. */
        fun outcome(): Pair<Boolean, Boolean> {
            val aFirst = a.firstStrike && !b.firstStrike
            val bFirst = b.firstStrike && !a.firstStrike
            val aDmg = a.power * if (a.doubleStrike) 2 else 1
            return when {
                aFirst -> { val first = lethal(a, b, a.power); (!first && lethal(b, a, b.power)) to (first || lethal(a, b, aDmg)) }
                bFirst -> { val ad = lethal(b, a, b.power); ad to (!ad && lethal(a, b, a.power)) }
                else -> lethal(b, a, b.power) to lethal(a, b, aDmg)
            }
        }
        // Combat tricks: the attacker, then the defender, may pump to win the fight.
        val before = outcome()
        if (before.first || !before.second) useTrick(p, p.pool, a) { val o = outcome(); (before.first && !o.first) || (!before.second && o.second) }
        val mid = outcome()
        if (mid.second || !mid.first) useTrick(defender, defender.open, b) { val o = outcome(); (mid.second && !o.second) || (!mid.first && o.first) }
        val (aDead, bDead) = outcome()
        fireCard(defender, b.card, b, Trigger.SELF_BLOCKS)
        if (b.power > 0) fireCard(p, a.card, a, Trigger.SELF_DAMAGED)
        if (a.power > 0) fireCard(defender, b.card, b, Trigger.SELF_DAMAGED)
        val bFirst = b.firstStrike && !a.firstStrike
        val aDmg = a.power * if (a.doubleStrike) 2 else 1
        // An attacker killed by a first striker deals no damage.
        val dealt = if (bFirst && aDead) 0 else aDmg
        p.track.dmgCreatures += minOf(dealt, b.toughness)
        defender.track.dmgCreatures += b.power
        if (a.lifelink && dealt > 0) gainLife(p, dealt)
        if (b.lifelink && b.power > 0) gainLife(defender, b.power)
        if (a.trample && dealt > 0) {
            val excess = dealt - (if (a.deathtouch) 1 else b.toughness)
            if (excess > 0) hitPlayer(p, a, defender, excess)
        }
        if (bDead) die(defender, b, null, a.card)
        if (aDead) die(p, a, null, b.card)
    }

    /** Casts the first combat trick from [owner]'s hand that [helps], paying from [mana]. */
    private fun useTrick(owner: Seat, mana: MutableList<Int>, creature: Perm, helps: () -> Boolean) {
        for (t in owner.hand.filter { it.pump > 0 }) {
            if (!payCost(t.cost, mana, commit = false)) continue
            creature.temp += t.pump
            if (helps()) {
                payCost(t.cost, mana, commit = true)
                owner.hand.remove(t); owner.graveyard += t
                noteCast(owner, t)
                return
            }
            creature.temp -= t.pump
        }
    }

    /** Combat damage to a player: life (or poison with infect), toxic, and "deals combat damage to a player" triggers. */
    private fun hitPlayer(p: Seat, a: Perm, defender: Seat, dmg: Int) {
        if (dmg <= 0 || !defender.alive) return
        lastDamage = dmg
        lastDamaged = defender
        if (a.has(Kw.INFECT)) {
            val ct = p.track.card(a.card.name)
            ct.damage += dmg; ct.combatDamage += dmg
            poison(p, defender, dmg, a.card)
        } else {
            damagePlayer(p, defender, dmg, a.card, combat = true, cause = EndCondition.COMBAT_DAMAGE)
        }
        if (a.card.toxic > 0) poison(p, defender, a.card.toxic, a.card)
        if (monarch === defender) monarch = p
        if (p.alive) fireCard(p, a.card, a, Trigger.COMBAT_DAMAGE)
        if (p.alive) p.nonLands().filter { it.attachedTo === a }.forEach { fireCard(p, it.card, it, Trigger.ATTACHED_DAMAGE, subject = a) }
        if (p.alive) fire(p, Trigger.CREATURE_COMBAT_DAMAGE, subject = a)
    }

    private fun eliminate(p: Seat, cause: EndCondition, by: String?) {
        if (!p.alive) return
        p.alive = false
        p.track.place = seats.count { it.alive } + 1
        p.track.eliminatedTurn = round + 1
        p.track.lossCause = cause
        p.track.lethalBy = by
        lastCause = cause
        if (monarch === p) monarch = null
        p.creatures.clear(); p.others.clear(); p.rocks.clear()
    }
}

/** The quick model as a [GamePlayer]. Thread-safe: each game gets its own state. */
internal class QuickGamePlayer : GamePlayer {
    @Volatile private var prepared: Map<String, QuickDeck> = emptyMap()
    override val parallelism: Int get() = maxOf(1, Runtime.getRuntime().availableProcessors() - 1).coerceAtMost(6)
    override fun prepare(decks: List<Deck>, control: SimControl) {
        prepared = decks.associate { it.id to QuickDeck.from(it) }
        val noData = decks.filter { d -> (d.commanders + d.cards).any { it.manaCost == null && it.produces == null && it.oracle == null } }
        if (noData.isNotEmpty()) control.log("Card data still loading for: ${noData.joinToString { it.name }} (results less accurate)")
        for (d in prepared.values) {
            val cards = (d.library + d.commanders).filter { !it.isLand }.distinctBy { it.name }
            val partial = cards.count { it.coverage.status != Coverage.Status.FULL }
            if (cards.isNotEmpty()) control.log("${d.deck.name}: the quick model fully reads ${cards.size - partial} of ${cards.size} non-land cards")
        }
    }
    override fun notes(): List<String> = buildList {
        val decks = prepared.values.toList()
        val lines = decks.map { d ->
            val cards = (d.library + d.commanders).filter { !it.isLand }.distinctBy { it.name }
            Triple(d, cards.count { it.coverage.status == Coverage.Status.FULL }, cards.size)
        }
        if (lines.isNotEmpty()) add("Quick model card coverage (fully read / non-land cards): " +
            lines.joinToString(" · ") { (d, full, all) -> "${d.deck.name} $full/$all" } + ". See each deck's screen for what's skipped.")
        decks.flatMap { d -> d.commanders.filter { it.coverage.status != Coverage.Status.FULL }.map { d to it } }.forEach { (d, c) ->
            add("Check: ${d.deck.name}'s commander ${c.name} is only partly read by the quick model (${c.coverage.missed.size} line${if (c.coverage.missed.size == 1) "" else "s"} skipped; see the deck screen).")
        }
        lines.filter { (_, full, all) -> all >= 20 && full < all * 0.6 }.forEach { (d, full, all) ->
            add("Check: the quick model only fully reads $full of ${d.deck.name}'s $all non-land cards, so its results for that deck are rough.")
        }
        decks.filter { it.combos.isNotEmpty() }.forEach { d -> add("${d.deck.name} can win with: ${d.combos.joinToString { it.name }}.") }
    }

    override fun play(seats: List<Deck>, seed: Long): GameResult =
        QuickGame(seats.map { prepared[it.id] ?: QuickDeck.from(it) }, Random(seed)).play()
}
