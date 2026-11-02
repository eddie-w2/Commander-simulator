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
    var buff = 0
    /** From anthems and lords in play; recomputed when they change. */
    var bonus = 0
    val power: Int get() = max(0, card.power + counters + buff + bonus)
    val toughness: Int get() = max(1, card.toughness + counters + buff + bonus)
    val token: Boolean get() = card.isToken
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

    fun play(): GameResult {
        seats.forEach { openingHand(it) }
        var winner: Int? = null
        loop@ while (round < QUICK_MAX_ROUNDS) {
            for (p in seats) {
                if (!p.alive) continue
                takeTurn(p)
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
            if (c == null) { eliminate(p, EndCondition.MILL, null); return }
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
        p.track.turnsTaken++
        for (q in seats) { q.usedOnce.clear(); q.triggerBudget = 40 }
        p.permanents().forEach { it.tapped = false; it.sick = false }
        fire(p, Trigger.UPKEEP)
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
        combat(p)
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
        for (s in p.allSources()) if (!s.tapped && !(s.card.isCreature && s.sick)) p.pool += (s.card.produces.takeIf { it != 0 } ?: ManaColor.C)
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
        (c.roles - Role.OTHER).let { r -> r.isNotEmpty() && r.all { it == Role.COUNTER || it == Role.PROTECTION || it == Role.REMOVAL } }

    private fun castSpells(p: Seat) {
        val held = p.hand.filter { isHeld(it) }.minByOrNull { it.mv }
        val reserve = if (held != null && rng.nextFloat() < 0.7f) held.cost.total else 0
        while (p.alive) {
            val cmd = p.commandZone.firstOrNull()
            if (cmd != null && (p.creatures.size < 3 || rng.nextFloat() < 0.5f)) {
                val cost = withTax(p, cmd)
                if (payCost(cost, p.pool, commit = true)) {
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
                !c.isLand && c.cost.total <= p.pool.size - (if (c === held) 0 else reserve) && wantsToCast(p, c) &&
                    payCost(c.cost, p.pool, commit = false)
            }
            val pick = playable.maxByOrNull { priority(it) } ?: break
            payCost(pick.cost, p.pool, commit = true)
            p.hand.remove(pick)
            p.track.manaByTurn[turnIdx] += pick.cost.total
            noteCast(p, pick)
            castTriggers(p, pick)
            if (!tryCounter(p, pick)) resolve(p, pick) else p.graveyard += pick
        }
    }

    private fun castTriggers(p: Seat, c: SimCard) {
        fire(p, Trigger.CAST_SPELL)
        if (!c.isCreature) fire(p, Trigger.CAST_NONCREATURE)
    }

    private fun priority(c: SimCard): Int = when {
        c.isManaSource || Role.RAMP_LAND in c.roles -> 100 - c.mv
        Role.WIPE in c.roles || Role.REMOVAL in c.roles -> 60 + c.mv
        Role.ENGINE in c.roles || Role.DRAW in c.roles || Role.TUTOR in c.roles || c.abilities.isNotEmpty() -> 50 + c.mv
        else -> 40 + c.mv
    }

    private fun wantsToCast(p: Seat, c: SimCard): Boolean = when {
        // Counterspells and protection wait for the opponents' turns.
        isHeld(c) && Role.REMOVAL !in c.roles -> false
        Role.WIPE in c.roles && !c.isCreature -> opponents(p).sumOf { it.boardPower } >= p.boardPower + 6
        // Instant removal is kept for an attacker unless there's a big threat now.
        isHeld(c) -> opponents(p).any { q -> q.creatures.any { it.power >= 4 || it.card.isCommander } || q.others.any { it.card.abilities.isNotEmpty() } }
        Role.REMOVAL in c.roles && !c.isCreature && c.roles.none { it == Role.DRAW || it == Role.TOKENS } ->
            opponents(p).any { q -> targets(q, c).isNotEmpty() }
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
            if (c.buff > 0) p.creatures.filter { !it.card.isManaSource }.maxByOrNull { it.power }?.let { it.buff += c.buff }
            enter(p, Perm(c, sick = !c.haste))
        } else {
            fireCard(p, c, null, Trigger.CAST)
        }
        if (!p.alive) return
        if (Role.RAMP_LAND in r) rampLand(p)
        if (Role.TUTOR in r && Role.DRAW !in r) draw(p, 1, counted = false)
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
            Role.ROCK in c.roles -> { p.rocks += perm; if (c.produces != 0) p.pool += c.produces }
            else -> p.others += perm
        }
        if (c.anthem > 0) recalcBonus(p) else if (c.isCreature) perm.bonus = bonusFor(p, perm)
        fireCard(p, c, perm, Trigger.ETB)
        if (c.isCreature) fire(p, Trigger.CREATURE_ETB, exclude = perm)
        if (c.isArtifact) fire(p, Trigger.ARTIFACT_ETB, exclude = perm)
    }

    private fun bonusFor(p: Seat, c: Perm): Int = p.nonLands().sumOf { a ->
        val lt = a.card.lordType
        when {
            a.card.anthem == 0 -> 0
            lt == null -> a.card.anthem
            a !== c && c.card.subtypes.any { it == lt } -> a.card.anthem
            else -> 0
        }
    }

    private fun recalcBonus(p: Seat) = p.creatures.forEach { it.bonus = bonusFor(p, it) }

    /** Fires [trigger] on every permanent [p] controls (or only [only]). */
    private fun fire(p: Seat, trigger: Trigger, only: Perm? = null, exclude: Perm? = null) {
        if (!p.alive) return
        for (perm in (if (only != null) listOf(only) else p.nonLands())) {
            if (perm === exclude) continue
            fireCard(p, perm.card, perm, trigger)
            if (!p.alive || p.triggerBudget <= 0) return
        }
    }

    private fun fireCard(p: Seat, c: SimCard, perm: Perm?, trigger: Trigger) {
        c.abilities.forEachIndexed { i, a ->
            if (a.trigger != trigger || p.triggerBudget <= 0 || !p.alive) return@forEachIndexed
            if (a.oncePerTurn && perm != null && !p.usedOnce.add(perm to i)) return@forEachIndexed
            p.triggerBudget--
            apply(p, c, perm, a.effects)
        }
    }

    private fun apply(p: Seat, src: SimCard, perm: Perm?, effects: List<Effect>) {
        for (e in effects) {
            if (!p.alive) return
            when (e) {
                is MakeTokens -> makeTokens(p, src, e)
                is Draw -> draw(p, e.n)
                is GainLife -> gainLife(p, e.n)
                is LoseLife -> (if (e.each) opponents(p) else listOfNotNull(opponents(p).minByOrNull { it.life })).forEach {
                    damagePlayer(p, it, e.n, src, combat = false, cause = EndCondition.LIFE_LOSS)
                }
                is Damage -> (if (e.each) opponents(p) else listOfNotNull(opponents(p).minByOrNull { it.life })).forEach {
                    damagePlayer(p, it, e.n, src, combat = false, cause = EndCondition.NONCOMBAT_DAMAGE)
                }
                is Counters -> when {
                    e.all -> p.creatures.forEach { it.counters += e.n }
                    e.self && perm != null && perm.card.isCreature -> perm.counters += e.n
                    else -> p.creatures.maxByOrNull { it.power }?.let { it.counters += e.n }
                }
            }
        }
    }

    private fun gainLife(p: Seat, n: Int) { p.life += n; p.track.lifeGained += n }

    private fun makeTokens(p: Seat, src: SimCard, t: MakeTokens) {
        when (t.kind) {
            TokenKind.CREATURE -> {
                val card = SimCard.token(t, src)
                repeat(t.count.coerceAtMost(10)) { if (p.alive) enter(p, Perm(card, sick = !card.haste)) }
                p.track.tokens += t.count
            }
            TokenKind.TREASURE -> { p.treasures += t.count; p.track.tokens += t.count; fire(p, Trigger.ARTIFACT_ETB) }
            TokenKind.FOOD -> { p.foods += t.count; p.track.tokens += t.count; fire(p, Trigger.ARTIFACT_ETB) }
            TokenKind.CLUE -> { p.clues += t.count; p.track.tokens += t.count; fire(p, Trigger.ARTIFACT_ETB) }
        }
    }

    /** After combat: activated abilities, Clues and Food with the mana that's left (keeping any held-up mana). */
    private fun spendLeftover(p: Seat) {
        val reserve = p.hand.filter { isHeld(it) }.minByOrNull { it.mv }?.cost?.total ?: 0
        for (perm in p.nonLands()) {
            for (a in perm.card.activated) {
                var uses = if (a.tap || a.sacrificeSelf) 1 else 2
                while (uses-- > 0 && p.alive) {
                    if (a.tap && (perm.tapped || (perm.card.isCreature && perm.sick))) break
                    if (perm !in p.creatures && perm !in p.others && perm !in p.rocks) break
                    if (p.pool.size - a.cost.total < reserve || !payCost(a.cost, p.pool, commit = true)) break
                    if (a.tap) perm.tapped = true
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

    private fun recur(p: Seat, c: SimCard) {
        val back = p.graveyard.filter { it.isPermanent && !it.isLand }.maxByOrNull { it.mv } ?: return
        p.graveyard.remove(back)
        if (c.recursionToBattlefield) enter(p, Perm(back, sick = !back.haste)) else p.hand += back
    }

    // ───────────────────────────── Removal ─────────────────────────────

    /** What [c] can target among [q]'s permanents. */
    private fun targets(q: Seat, c: SimCard): List<Perm> = q.nonLands().filter { v ->
        !v.card.has(Kw.HEXPROOF) &&
            !(v.card.has(Kw.INDESTRUCTIBLE) && (c.removalKind == RemovalKind.DESTROY || c.removalKind == RemovalKind.DAMAGE)) &&
            !(c.removalKind == RemovalKind.DAMAGE && (!v.card.isCreature || v.toughness > c.removalDamage.coerceAtLeast(2)))
    }

    private fun value(v: Perm): Int = v.power * 2 + (if (v.card.isCommander) 6 else 0) + v.card.abilities.size * 3 +
        v.card.anthem * 4 + v.card.mv + (if (v.card.has(Kw.WARD)) -3 else 0)

    private fun removeOne(p: Seat, c: SimCard, only: Perm? = null, onlyOwner: Seat? = null) {
        val choice = if (only != null && onlyOwner != null) onlyOwner to only else
            opponents(p).flatMap { q -> targets(q, c).map { q to it } }.maxByOrNull { (q, v) -> value(v) + threat(q) / 4 } ?: return
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
            else -> die(target, victim, p, c)
        }
    }

    private fun wipe(p: Seat, c: SimCard) {
        var others = 0
        var total = 0
        val exile = c.removalKind == RemovalKind.EXILE
        for (q in seats.filter { it.alive }) for (victim in q.creatures.toList()) {
            if (!exile && victim.card.has(Kw.INDESTRUCTIBLE)) continue
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
            victim.token -> Unit
            victim.card.isCommander -> { if (byOpponent != null) owner.track.cmdRemoved++; owner.commandZone += victim.card }
            toGraveyard -> owner.graveyard += victim.card
        }
        if (victim.card.anthem > 0) recalcBonus(owner)
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

    private fun canBlock(b: Perm, a: Perm): Boolean =
        !b.tapped && !b.card.has(Kw.CANT_BLOCK) && !a.card.unblockable && (!a.card.flying || b.card.flying || b.card.reach)

    private fun evasive(a: Perm, blockers: List<Perm>) =
        blockers.none { canBlock(it, a) } || (a.card.menace && blockers.count { canBlock(it, a) } < 2)

    private fun combat(p: Seat) {
        val ready = p.creatures.filter {
            !it.sick && !it.tapped && it.power > 0 && !it.card.has(Kw.DEFENDER) && !(it.card.isManaSource && it.power <= 1)
        }
        if (ready.isEmpty()) return
        val opps = opponents(p)
        fun blockersOf(q: Seat) = q.creatures.filter { !it.tapped && !it.card.has(Kw.CANT_BLOCK) }
        // Attack where the most damage gets through (open boards, low life), going for a kill when one is on.
        fun through(q: Seat): Int {
            val bl = blockersOf(q)
            val blockable = ready.filter { !evasive(it, bl) }.sortedByDescending { it.power }
            return ready.filter { evasive(it, bl) }.sumOf { it.power } + blockable.drop(bl.size).sumOf { it.power }
        }
        val defender = opps.firstOrNull { q -> through(q) >= q.life }
            ?: opps.maxByOrNull { q -> through(q) * 4 - q.life + threat(q) / 3 + rng.nextInt(6) }
            ?: return
        val blockers = blockersOf(defender).toMutableList()
        // Keep blockers home when the table could kill us on the crack-back.
        val incoming = opps.maxOf { q -> q.creatures.filter { !it.card.has(Kw.DEFENDER) }.sumOf { it.power } }
        // (Only at low life, and only one: the AI otherwise stalls boards far more than real tables do.)
        val keepCount = if (incoming >= p.life && p.life <= 12) 1 else 0
        val keep = ready.filter { !it.card.has(Kw.VIGILANCE) }.sortedByDescending { it.toughness }.take(keepCount).toSet()
        val attackers = (ready - keep).filter { a ->
            // Don't send a creature into a blocker that kills it and survives, unless it can't be blocked well.
            evasive(a, blockers) || blockers.none { b -> canBlock(b, a) && b.power >= a.toughness && b.toughness > a.power } || rng.nextFloat() < 0.25f
        }.toMutableList()
        if (attackers.isEmpty()) return
        p.track.attacks++
        p.track.attackersTotal += attackers.size
        fire(p, Trigger.ATTACK)
        attackers.toList().forEach { if (p.alive) fire(p, Trigger.SELF_ATTACK, only = it) }
        attackers.forEach { if (!it.card.has(Kw.VIGILANCE)) it.tapped = true }
        attackers.retainAll { it in p.creatures }
        if (!p.alive || !defender.alive) return

        // The defender may kill the biggest attacker with instant removal before blocks.
        defender.hand.firstOrNull { c -> isHeld(c) && Role.REMOVAL in c.roles && payCost(c.cost, defender.open, commit = false) }?.let { c ->
            val legal = targets(p, c)
            val target = attackers.filter { it in legal }.maxByOrNull { it.power }
            if (target != null && target.power >= 3) {
                payCost(c.cost, defender.open, commit = true)
                defender.hand.remove(c); defender.graveyard += c
                noteCast(defender, c)
                removeOne(defender, c, only = target, onlyOwner = p)
                attackers.retainAll { it in p.creatures }
            }
        }

        var incomingNow = attackers.sumOf { it.power * if (it.card.doubleStrike) 2 else 1 }
        for (a in attackers.sortedByDescending { it.power }) {
            if (!defender.alive || !p.alive) break
            if (a !in p.creatures) continue
            val danger = defender.life <= incomingNow
            val options = blockers.filter { canBlock(it, a) }.let { if (a.card.menace && it.size < 2) emptyList() else it }
            val blocker = options.filter { b ->
                (b.toughness > a.power && !a.card.deathtouch) || (b.power >= a.toughness && b.card.mv <= a.card.mv) || danger
            }.maxByOrNull { it.toughness * 2 + it.power }
            val strikes = if (a.card.doubleStrike) 2 else 1
            incomingNow -= a.power * strikes
            if (blocker != null && (danger || rng.nextFloat() < 0.6f)) {
                blockers.remove(blocker)
                defender.track.blocks++
                fight(p, a, defender, blocker)
            } else {
                val dmg = a.power * strikes
                if (a.card.lifelink) gainLife(p, dmg)
                damagePlayer(p, defender, dmg, a.card, combat = true, cause = EndCondition.COMBAT_DAMAGE)
            }
        }
    }

    /** One attacker blocked by one blocker, with first/double strike, deathtouch, trample, lifelink and indestructible. */
    private fun fight(p: Seat, a: Perm, defender: Seat, b: Perm) {
        fun lethal(src: Perm, dst: Perm, dmg: Int) = dmg > 0 && (dmg >= dst.toughness || src.card.deathtouch) && !dst.card.has(Kw.INDESTRUCTIBLE)
        val aFirst = a.card.firstStrike && !b.card.firstStrike
        val bFirst = b.card.firstStrike && !a.card.firstStrike
        val aDmg = a.power * if (a.card.doubleStrike) 2 else 1
        val bDead: Boolean
        val aDead: Boolean
        when {
            aFirst -> { val first = lethal(a, b, a.power); aDead = !first && lethal(b, a, b.power); bDead = first || lethal(a, b, aDmg) }
            bFirst -> { aDead = lethal(b, a, b.power); bDead = !aDead && lethal(a, b, a.power) }
            else -> { bDead = lethal(a, b, aDmg); aDead = lethal(b, a, b.power) }
        }
        // An attacker killed by a first striker deals no damage.
        val dealt = if (bFirst && aDead) 0 else aDmg
        p.track.dmgCreatures += minOf(dealt, b.toughness)
        defender.track.dmgCreatures += b.power
        if (a.card.lifelink && dealt > 0) gainLife(p, dealt)
        if (b.card.lifelink && b.power > 0) gainLife(defender, b.power)
        if (a.card.trample && dealt > 0) {
            val excess = dealt - (if (a.card.deathtouch) 1 else b.toughness)
            if (excess > 0) damagePlayer(p, defender, excess, a.card, combat = true, cause = EndCondition.COMBAT_DAMAGE)
        }
        if (bDead) die(defender, b, null, a.card)
        if (aDead) die(p, a, null, b.card)
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
    override fun play(seats: List<Deck>, seed: Long): GameResult =
        QuickGame(seats.map { prepared[it.id] ?: QuickDeck.from(it) }, Random(seed)).play()
}
