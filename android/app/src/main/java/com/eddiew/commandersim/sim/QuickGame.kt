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
    val power: Int get() = max(0, card.power + counters + bonus + temp)
    val toughness: Int get() = max(1, card.toughness + counters + bonus + temp)
    val token: Boolean get() = card.isToken
    fun has(bit: Int) = card.has(bit) || (granted or tempKw) and bit != 0
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
    /** The last combat damage to a player, for "that player mills that many". */
    private var lastDamage = 0
    private var lastDamaged: Seat? = null

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
        for (q in seats) { q.usedOnce.clear(); q.triggerBudget = 40; q.lifeGainedTurn = 0 }
        p.permanents().forEach { it.tapped = false; it.sick = false }
        fire(p, Trigger.UPKEEP)
        if (!p.alive) return
        sagasAndWalkers(p)
        // Draw engines whose text wasn't read as a draw trigger keep a simple "1 card a turn".
        p.nonLands().filter { Role.ENGINE in it.card.roles && !it.card.parses(Draw::class.java) }.forEach { draw(p, it.card.draws.coerceAtLeast(1)) }
        if (!p.alive) return
        p.track.handByTurn[t] = p.hand.size
        draw(p, 1)
        if (!p.alive) return

        playLand(p)
        refillPool(p)

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

        p.treasures = p.pool.count { it and TREASURE != 0 }
        p.open.clear(); p.open.addAll(p.pool)
        while (p.hand.size > 7) {
            val worst = p.hand.filter { !it.isLand }.maxByOrNull { it.mv } ?: p.hand.last()
            p.hand.remove(worst); p.graveyard += worst; p.track.discarded++
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
        for (s in p.allSources()) {
            if (s.tapped || (s.card.isCreature && s.sick)) continue
            val color = s.card.produces.takeIf { it != 0 } ?: ManaColor.C
            val extra = if (s.card.isLand) landExtra else 0
            val amount = (s.card.manaScale?.let { count(p, it) } ?: 1) * s.card.manaAmount - s.card.manaActivation + extra
            repeat(amount.coerceIn(if (s.card.manaActivation > 0) 0 else 1, 30)) { p.pool += color }
        }
        val any = (p.deck.colors and ManaColor.ALL).let { if (it == 0) ManaColor.ALL else it }
        repeat(p.treasures) { p.pool += any or TREASURE }
    }

    /** Plays the land that best fixes the colours the hand needs, preferring one that enters untapped. */
    private fun playLand(p: Seat) {
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
                else -> c.type == CardType.INSTANT || c.type == CardType.SORCERY
            }
            if (ok) off += r.reduceBy
        }
        return if (off == 0) base else base.copy(generic = (base.generic - off).coerceAtLeast(0))
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
            if (!pay(p, pick, costOf(p, pick), if (pick === held) 0 else reserve)) break
            p.hand.remove(pick)
            p.track.manaByTurn[turnIdx] += pick.cost.total
            noteCast(p, pick)
            castTriggers(p, pick)
            if (!tryCounter(p, pick)) resolve(p, pick) else p.graveyard += pick
            castX = 0
            if (tryCombo(p)) return
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
        fire(p, Trigger.CAST_SPELL)
        if (!c.isCreature) fire(p, Trigger.CAST_NONCREATURE)
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
        if (spell.mv < 3) return false
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
        if (Role.RAMP_LAND in r) rampLand(p)
        if (Role.TUTOR in r) c.tutor?.let { tutor(p, it) } ?: if (Role.DRAW !in r) draw(p, 1, counted = false) else Unit
        if (Role.WIPE in r) wipe(p, c)
        else if (Role.REMOVAL in r) removeOne(p, c)
        if (Role.RECURSION in r) recur(p, c)
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
    }

    /** Whether [c] is of creature type [tribe] ("chosen" = the deck's main type, "token" = any token). */
    private fun isTribe(p: Seat, c: Perm, tribe: String?): Boolean = when (tribe) {
        null -> true
        "token" -> c.token
        "chosen" -> p.deck.mainTribe != null && p.deck.mainTribe in c.card.subtypes
        else -> tribe in c.card.subtypes
    }

    private fun lordApplies(p: Seat, lord: Perm, c: Perm): Boolean {
        val lt = lord.card.lordType ?: return true
        return (lt == "token" || lord !== c) && isTribe(p, c, lt)
    }

    private fun bonusFor(p: Seat, c: Perm): Int = p.nonLands().sumOf { a ->
        val attached = if (a.attachedTo === c) a.card.buff else 0
        attached + if (a.card.anthem != 0 && lordApplies(p, a, c)) a.card.anthem else 0
    }

    private fun recalcBonus(p: Seat) {
        val attachments = p.others.filter { it.attachedTo != null } + p.rocks.filter { it.attachedTo != null }
        val teamSources = p.nonLands().filter { it.card.teamGrants != 0 }
        p.creatures.forEach { cr ->
            cr.bonus = bonusFor(p, cr)
            val team = teamSources.fold(0) { m, a -> if (a === cr && a.card.teamGrantsOthers) m else m or a.card.teamGrants }
            val lords = p.nonLands().filter { (it.card.lordGrants != 0 || it.card.lordLandwalk != 0) && lordApplies(p, it, cr) }
            cr.granted = team or lords.fold(0) { m, a -> m or a.card.lordGrants } or attachments.filter { it.attachedTo === cr }.fold(0) { m, a -> m or a.card.grants }
            cr.grantedWalk = lords.fold(0) { m, a -> m or a.card.lordLandwalk }
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
    }

    /** An effect's amount: X is the X paid (or 2), "for each" multiplies. */
    private fun amount(p: Seat, n: Int, scale: Scale?): Int {
        val base = when (n) { XN -> if (castX > 0) castX else 2; DMG -> lastDamage; LIFE_GAINED -> p.lifeGainedTurn; else -> n }
        return if (scale == null) base else (if (n == XN) 1 else base) * count(p, scale)
    }

    /** Fires [trigger] on every permanent [p] controls (or only [only]), plus eminence abilities in the command zone. */
    private fun fire(p: Seat, trigger: Trigger, only: Perm? = null, exclude: Perm? = null, subject: Perm? = null) {
        if (!p.alive) return
        for (perm in (if (only != null) listOf(only) else p.nonLands())) {
            if (perm === exclude) continue
            fireCard(p, perm.card, perm, trigger, subject)
            if (!p.alive || p.triggerBudget <= 0) return
        }
        if (only == null) for (c in p.commandZone.toList()) fireCard(p, c, null, trigger, subject, commandZone = true)
    }

    private fun fireCard(p: Seat, c: SimCard, perm: Perm?, trigger: Trigger, subject: Perm? = null, commandZone: Boolean = false) {
        c.abilities.forEachIndexed { i, a ->
            if (a.trigger != trigger || p.triggerBudget <= 0 || !p.alive) return@forEachIndexed
            if (commandZone && !a.fromCommandZone) return@forEachIndexed
            // "Whenever another Cat you control attacks": the attacker must be that type, and not the source itself.
            if (a.tribe != null && (subject == null || !isTribe(p, subject, a.tribe))) return@forEachIndexed
            if ((trigger == Trigger.CREATURE_ATTACKS || trigger == Trigger.CREATURE_ETB) && subject != null && subject === perm) return@forEachIndexed
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
                    targets.forEach { t ->
                        if (e.doublePower) t.temp += t.power else t.temp += e.power
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
                is Draw -> draw(p, amount(p, e.n, e.scale).coerceAtMost(10))
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
                    damagePlayer(p, it, amount(p, e.n, e.scale), src, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE)
                }
                is Counters -> {
                    val k = amount(p, e.n, e.scale)
                    when {
                        // "Distribute that many counters": spread over your best creatures.
                        e.n == LIFE_GAINED -> p.creatures.filter { it !== perm }.sortedByDescending { it.power }.take(k).forEach { addCounters(p, it, 1) }
                        e.all -> p.creatures.forEach { addCounters(p, it, k) }
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
            }
        }
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

    private fun gainLife(p: Seat, n: Int) { p.life += n; p.track.lifeGained += n; p.lifeGainedTurn += n }

    /** +1/+1 counters, with Hardened Scales (+1) and doublers (×2) applied. */
    private fun addCounters(p: Seat, c: Perm, k: Int) {
        if (k <= 0) return
        var n = k
        p.nonLands().forEach { n += it.card.counterPlus }
        p.nonLands().forEach { n *= it.card.counterMultiplier }
        c.counters += n.coerceAtMost(40)
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
                when (t.kind) { TokenKind.TREASURE -> p.treasures += n; TokenKind.FOOD -> p.foods += n; else -> p.clues += n }
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
                    // Sacrifice outlets only eat small tokens, never real cards.
                    val fodder = if (a.sacrificeCreature) p.creatures.filter { it.token && it.power <= 2 && it !== perm && isTribe(p, it, a.sacrificeTribe) }
                        .minByOrNull { it.power } ?: break else null
                    val tappers = if (a.tapCreatures > 0) p.creatures.filter { !it.tapped && it !== perm && isTribe(p, it, a.tapTribe) }
                        .sortedBy { it.power }.take(a.tapCreatures).takeIf { it.size == a.tapCreatures } ?: break else emptyList()
                    if (p.pool.size - a.cost.total < reserve || !payCost(a.cost, p.pool, commit = true)) break
                    if (a.tap) perm.tapped = true
                    if (fodder != null) { p.track.sacrificed++; die(p, fodder, null, perm.card) }
                    tappers.forEach { it.tapped = true }
                    apply(p, perm.card, perm, a.effects)
                    if (a.sacrificeSelf) { die(p, perm, null, perm.card); break }
                }
            }
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

    /** What [c] can target among [q]'s permanents. */
    private fun targets(q: Seat, c: SimCard, from: Seat): List<Perm> {
        val fighter = if (c.removalKind == RemovalKind.FIGHT) from.creatures.maxByOrNull { it.power } else null
        return q.nonLands().filter { v ->
            !v.has(Kw.HEXPROOF) &&
                !(v.has(Kw.INDESTRUCTIBLE) && (c.removalKind == RemovalKind.DESTROY || c.removalKind == RemovalKind.DAMAGE || c.removalKind == RemovalKind.FIGHT)) &&
                when (c.removalKind) {
                    RemovalKind.DAMAGE -> v.card.isCreature && v.toughness <= c.removalDamage.coerceAtLeast(2)
                    RemovalKind.MINUS -> v.card.isCreature && v.toughness <= c.removalDamage.coerceAtLeast(1)
                    RemovalKind.FIGHT -> v.card.isCreature && fighter != null && (fighter.power >= v.toughness || fighter.deathtouch)
                    else -> true
                }
        }
    }

    private fun value(v: Perm): Int = v.power * 2 + (if (v.card.isCommander) 6 else 0) + v.card.abilities.size * 3 +
        v.card.anthem * 4 + v.card.mv + (if (v.has(Kw.WARD)) -3 else 0)

    private fun removeOne(p: Seat, c: SimCard, only: Perm? = null, onlyOwner: Seat? = null) {
        val choice = if (only != null && onlyOwner != null) onlyOwner to only else
            opponents(p).flatMap { q -> targets(q, c, p).map { q to it } }.maxByOrNull { (q, v) -> value(v) + threat(q) / 4 } ?: return
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
        when (c.removalKind) {
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
        val exile = c.removalKind == RemovalKind.EXILE
        for (q in seats.filter { it.alive }) for (victim in q.creatures.toList()) {
            if (!exile && victim.has(Kw.INDESTRUCTIBLE)) continue
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
    private fun die(owner: Seat, victim: Perm, byOpponent: Seat?, by: SimCard) {
        if (!leave(owner, victim, byOpponent, by, toGraveyard = true)) return
        if (victim.card.isCreature) {
            fireCard(owner, victim.card, victim, Trigger.SELF_DIES)
            fire(owner, Trigger.OWN_DIES)
            for (q in seats) fire(q, Trigger.DIES)
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

    private fun damagePlayer(src: Seat, dst: Seat, amount: Int, card: SimCard, combat: Boolean, cause: EndCondition) {
        if (!dst.alive || amount <= 0) return
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

    private fun canBlock(b: Perm, a: Perm): Boolean =
        !b.tapped && !b.has(Kw.CANT_BLOCK) && !a.unblockable && (!a.flying || b.flying || b.reach) &&
            ((a.card.landwalk or a.grantedWalk) and blockingLands == 0) &&
            (!a.has(Kw.FEAR) || b.card.isArtifact || b.card.cost.pips.any { it and ManaColor.B != 0 })

    /** The defending player's land colours during the current combat. */
    private var blockingLands = 0

    private fun evasive(a: Perm, blockers: List<Perm>) =
        blockers.none { canBlock(it, a) } || (a.menace && blockers.count { canBlock(it, a) } < 2)

    private fun combat(p: Seat) {
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
            !it.sick && !it.tapped && it.power > 0 && !it.has(Kw.DEFENDER) && !(it.card.isManaSource && it.power <= 1)
        }
        if (ready.isEmpty()) return
        val opps = opponents(p)
        fun blockersOf(q: Seat) = q.creatures.filter { !it.tapped && !it.has(Kw.CANT_BLOCK) }
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
        val blockers = blockersOf(defender).toMutableList()
        // Keep blockers home when the table could kill us on the crack-back.
        val incoming = opps.maxOf { q -> q.creatures.filter { !it.has(Kw.DEFENDER) }.sumOf { it.power } }
        // (Only at low life, and only one: the AI otherwise stalls boards far more than real tables do.)
        val keepCount = if (incoming >= p.life && p.life <= 12) 1 else 0
        val keep = ready.filter { !it.has(Kw.VIGILANCE) }.sortedByDescending { it.toughness }.take(keepCount).toSet()
        val attackers = (ready - keep).filter { a ->
            // Don't send a creature into a blocker that kills it and survives, unless it can't be blocked well.
            evasive(a, blockers) || blockers.none { b -> canBlock(b, a) && b.power >= a.toughness && b.toughness > a.power } || rng.nextFloat() < 0.25f
        }.toMutableList()
        if (attackers.isEmpty()) return
        p.track.attacks++
        p.track.attackersTotal += attackers.size
        // Exalted: a lone attacker gets +1/+1 for each exalted permanent.
        if (attackers.size == 1) p.nonLands().count { it.has(Kw.EXALTED) }.takeIf { it > 0 }?.let { attackers[0].temp += it }
        fire(p, Trigger.ATTACK)
        attackers.toList().forEach {
            if (p.alive) fire(p, Trigger.SELF_ATTACK, only = it)
            if (p.alive) fire(p, Trigger.CREATURE_ATTACKS, subject = it)
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
        fun lethal(src: Perm, dst: Perm, dmg: Int) = dmg > 0 && (dmg >= dst.toughness || src.deathtouch) && !dst.has(Kw.INDESTRUCTIBLE)
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
        if (p.alive) fireCard(p, a.card, a, Trigger.COMBAT_DAMAGE)
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
