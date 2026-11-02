package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.EndCondition
import kotlin.math.max
import kotlin.random.Random

/*
 * The quick model: a fast Commander goldfish-plus-interaction model built on each card's real Scryfall data.
 *
 * Accurate where it matters for deck building:
 *  - mana: costs are paid pip by pip from the sources actually in play (lands, rocks, dorks), with real produced
 *    colours, tapped lands, fetches and land ramp; land drops pick the land that fixes the hand's colours.
 *  - curve: spells are cast when their real cost can be paid.
 * Simplified: card effects come from roles (removal, wipe, counter, draw, ramp, burn, drain, tokens…) detected from
 * Scryfall tags and rules text; combat uses real power/toughness and a few keywords; no stack, no politics, no combos.
 */

/** Round cap of the quick model. */
internal const val QUICK_MAX_ROUNDS = 18

/** Prepared deck: every card's model, by deck id. */
internal class QuickDeck(val deck: Deck, val library: List<SimCard>, val commanders: List<SimCard>, val colors: Int) {
    companion object {
        fun from(deck: Deck): QuickDeck {
            val colors = ManaColor.mask(deck.colorIdentity)
            val lib = deck.cards.flatMap { c -> List(c.quantity) { SimCard.from(c, colors) } }
            return QuickDeck(deck, lib, deck.commanders.map { SimCard.from(it, colors, commander = true) }, colors)
        }
    }
}

/** A permanent on the battlefield. */
private class Perm(val card: SimCard, var power: Int, var toughness: Int, var sick: Boolean, val token: Boolean = false) {
    var tapped = false
}

private class Seat(val index: Int, val deck: QuickDeck, rng: Random, seats: Int) {
    val library = ArrayDeque(deck.library.shuffled(rng))
    val hand = mutableListOf<SimCard>()
    val lands = mutableListOf<Perm>()
    val rocks = mutableListOf<Perm>()
    val creatures = mutableListOf<Perm>()
    val others = mutableListOf<Perm>()
    val commandZone = deck.commanders.toMutableList()
    val cmdCasts = HashMap<String, Int>()
    val cmdDamageFrom = IntArray(seats)
    /** Sum of anthems in play ("creatures you control get +N/+N"). */
    var anthem = 0
    /** Lands drawn so far, opening hand included. */
    var landsSeen = 0
    var life = 40
    var alive = true
    /** Mana sources still untapped this turn, as colour masks. */
    val pool = mutableListOf<Int>()
    val track = PlayerTrack(seats)

    val boardPower get() = creatures.sumOf { it.power }
    fun allSources(): List<Perm> = lands + rocks + creatures.filter { it.card.isManaSource }
    fun permanents(): List<Perm> = lands + rocks + creatures + others
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
        // Generic is paid with the least useful sources.
        repeat(cost.generic) { left.remove(left.minByOrNull { Integer.bitCount(it and ManaColor.ALL) }!!) }
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
        if (winner == null && alive.size >= 2) {
            // Round limit: score on life and board, like the Forge games.
            val scored = alive.sortedByDescending { it.life + 2 * it.boardPower + it.permanents().size }
            fun score(s: Seat) = s.life + 2 * s.boardPower + s.permanents().size
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

    private fun draw(p: Seat, count: Int, turnIdx: Int?) {
        repeat(count) {
            val c = p.library.removeFirstOrNull()
            if (c == null) { eliminate(p, EndCondition.MILL, null); return }
            p.hand += c
            if (c.isLand) p.landsSeen++
            val ct = p.track.card(c.name)
            if (!ct.seen) { ct.seen = true; ct.drawnRound = round }
            if (turnIdx != null) { p.track.drawn++; p.track.drawsByTurn[turnIdx]++ }
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

    private fun noteCast(p: Seat, c: SimCard, t: Int) {
        val ct = p.track.card(c.name)
        ct.casts++
        if (ct.firstCastRound < 0) ct.firstCastRound = t + 1
    }

    // ───────────────────────────── Turn ─────────────────────────────

    private fun takeTurn(p: Seat) {
        val t = (p.track.turnsTaken).coerceAtMost(MAX_TURNS - 1)
        p.track.turnsTaken++
        p.permanents().forEach { it.tapped = false; it.sick = false }
        p.track.handByTurn[t] = p.hand.size
        draw(p, 1, t)
        if (!p.alive) return
        // Upkeep/draw-step engines.
        p.others.plus(p.creatures).filter { Role.ENGINE in it.card.roles && !it.token }.forEach { draw(p, it.card.draws.coerceAtLeast(1), t) }
        if (!p.alive) return

        playLand(p, t)
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
        castSpells(p, t)
        if (!p.alive) return
        combat(p, t)

        while (p.hand.size > 7) {
            val worst = p.hand.filter { !it.isLand }.maxByOrNull { it.mv } ?: p.hand.last()
            p.hand.remove(worst); p.track.discarded++
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
    }

    /** Plays the land that best fixes the colours the hand needs, preferring one that enters untapped. */
    private fun playLand(p: Seat, t: Int) {
        val lands = p.hand.filter { it.isLand }
        if (lands.isEmpty()) return
        val have = p.allSources().fold(0) { m, s -> m or s.card.produces }
        val need = (p.hand.filter { !it.isLand } + p.commandZone).fold(0) { m, c -> m or c.cost.colorsNeeded } and ManaColor.ALL
        val pick = lands.maxByOrNull { l ->
            val newColors = Integer.bitCount(l.produces and need and have.inv())
            newColors * 10 + Integer.bitCount(l.produces and need) * 2 + (if (l.entersTapped) 0 else 3)
        }!!
        p.hand.remove(pick)
        p.lands += Perm(pick, 0, 0, sick = false).also { it.tapped = pick.entersTapped }
        p.track.landsPlayed++
        p.track.landByTurn[t] = true
        noteCast(p, pick, t)
    }

    private fun opponents(p: Seat) = seats.filter { it.alive && it !== p }
    private fun biggestThreat(p: Seat) = opponents(p).maxByOrNull { it.boardPower * 2 + it.rocks.size + it.others.size + (40 - it.life) / 4 }

    private fun castSpells(p: Seat, t: Int) {
        // Hold up the cheapest counterspell when there's mana to spare.
        val counter = p.hand.filter { Role.COUNTER in it.roles }.minByOrNull { it.mv }
        val reserve = if (counter != null && rng.nextFloat() < 0.6f) counter.mv else 0
        while (p.alive) {
            val cmd = p.commandZone.firstOrNull()
            if (cmd != null && (p.creatures.size < 3 || rng.nextFloat() < 0.5f)) {
                val cost = withTax(p, cmd)
                if (payCost(cost, p.pool, commit = true)) {
                    p.commandZone.remove(cmd)
                    p.track.manaByTurn[t] += cost.total
                    p.cmdCasts[cmd.name] = (p.cmdCasts[cmd.name] ?: 0) + 1
                    p.track.cmdCasts++
                    if (p.track.cmdFirstCast < 0) p.track.cmdFirstCast = t + 1
                    noteCast(p, cmd, t)
                    if (!tryCounter(p, cmd)) resolve(p, cmd, t) else p.commandZone += cmd
                    continue
                }
            }
            val playable = p.hand.filter { c ->
                // Pure counterspells are held for the opponents' turns (see tryCounter).
                !c.isLand && c.roles - Role.OTHER != setOf(Role.COUNTER) &&
                    c.cost.total <= p.pool.size - reserve && wantsToCast(p, c) && payCost(c.cost, p.pool, commit = false)
            }
            val pick = playable.maxByOrNull { priority(it) } ?: break
            payCost(pick.cost, p.pool, commit = true)
            p.hand.remove(pick)
            p.track.manaByTurn[t] += pick.cost.total
            noteCast(p, pick, t)
            if (!tryCounter(p, pick)) resolve(p, pick, t)
        }
    }

    private fun priority(c: SimCard): Int = when {
        c.isManaSource || Role.RAMP_LAND in c.roles -> 100 - c.mv
        Role.WIPE in c.roles || Role.REMOVAL in c.roles -> 60 + c.mv
        Role.ENGINE in c.roles || Role.DRAW in c.roles || Role.TUTOR in c.roles -> 50 + c.mv
        else -> 40 + c.mv
    }

    private fun wantsToCast(p: Seat, c: SimCard): Boolean = when {
        Role.WIPE in c.roles && !c.isCreature -> opponents(p).sumOf { it.boardPower } >= p.boardPower + 6
        Role.REMOVAL in c.roles && !c.isCreature && c.roles.none { it == Role.DRAW || it == Role.TOKENS } ->
            opponents(p).any { it.creatures.isNotEmpty() || it.others.isNotEmpty() || it.rocks.isNotEmpty() }
        else -> true
    }

    /** An opponent holding a counterspell (and the mana for it) may counter a meaningful spell. */
    private fun tryCounter(caster: Seat, spell: SimCard): Boolean {
        if (spell.mv < 3) return false
        for (q in opponents(caster)) {
            val counter = q.hand.firstOrNull { Role.COUNTER in it.roles } ?: continue
            val pool = q.allSources().map { it.card.produces.takeIf { m -> m != 0 } ?: ManaColor.C }.toMutableList()
            if (!payCost(counter.cost, pool, commit = false)) continue
            if (rng.nextFloat() < 0.5f) {
                q.hand.remove(counter)
                noteCast(q, counter, round)
                q.track.counters++
                q.track.card(counter.name).removals++
                caster.track.card(spell.name).countered++
                return true
            }
        }
        return false
    }

    private fun resolve(p: Seat, c: SimCard, t: Int) {
        val r = c.roles
        when {
            c.isCreature -> p.creatures += Perm(c, c.power + p.anthem, max(1, c.toughness + p.anthem), sick = !c.haste)
            Role.ROCK in r -> p.rocks += Perm(c, 0, 0, sick = false).also { p.pool += c.produces }
            c.type in setOf(CardType.ARTIFACT, CardType.ENCHANTMENT, CardType.PLANESWALKER, CardType.BATTLE) -> p.others += Perm(c, 0, 0, sick = false)
        }
        if (c.anthem > 0) {
            p.anthem += c.anthem
            p.creatures.forEach { it.power += c.anthem; it.toughness += c.anthem }
        }
        if (c.buff > 0) p.creatures.filter { !it.card.isManaSource }.maxByOrNull { it.power }?.let { it.power += c.buff; it.toughness += c.buff }
        if (Role.RAMP_LAND in r) rampLand(p)
        if (Role.DRAW in r) draw(p, c.draws.coerceAtLeast(1), t)
        if (Role.TUTOR in r && Role.DRAW !in r) draw(p, 1, t)
        if (Role.WIPE in r) wipe(p, c)
        else if (Role.REMOVAL in r) removeOne(p, c)
        if (Role.BURN in r) burn(p, c, t)
        if (Role.DRAIN in r) drain(p, c, t)
        if (Role.TOKENS in r && c.tokenPower > 0) {
            p.creatures += Perm(c, c.tokenPower + p.anthem, c.tokenPower + p.anthem, sick = true, token = true)
            p.track.tokens += max(1, c.tokenPower)
        }
        if (Role.LIFEGAIN in r && Role.DRAIN !in r) { p.life += 3; p.track.lifeGained += 3 }
    }

    /** Puts a land from the library onto the battlefield tapped, choosing one that adds a needed colour. */
    private fun rampLand(p: Seat) {
        val have = p.allSources().fold(0) { m, s -> m or s.card.produces }
        val need = p.deck.colors and have.inv()
        val land = p.library.filter { it.isLand }
            .maxByOrNull { Integer.bitCount(it.produces and need) * 10 + Integer.bitCount(it.produces) } ?: return
        p.library.remove(land)
        p.lands += Perm(land, 0, 0, sick = false).also { it.tapped = true }
    }

    private fun removeOne(p: Seat, c: SimCard) {
        val target = biggestThreat(p) ?: return
        val victim = target.creatures.maxByOrNull { it.power + if (it.card.isCommander) 3 else 0 }
            ?: target.others.maxByOrNull { it.card.mv }
            ?: target.rocks.maxByOrNull { it.card.mv }
            ?: return
        p.track.removalSpells++
        p.track.removalAtSeat[target.index]++
        p.track.card(c.name).removals++
        leaveBattlefield(target, victim, byOpponent = p, by = c)
    }

    private fun wipe(p: Seat, c: SimCard) {
        var others = 0
        var total = 0
        for (q in seats.filter { it.alive }) for (victim in q.creatures.toList()) {
            total++
            if (q !== p) {
                others++
                p.track.removalAtSeat[q.index]++
                leaveBattlefield(q, victim, byOpponent = p, by = c)
            } else {
                p.track.sacrificed++
                leaveBattlefield(q, victim, byOpponent = null, by = c)
            }
        }
        p.track.removalSpells++
        p.track.card(c.name).removals += others
        if (total >= 3 && others >= 2) p.track.wipes++
    }

    private fun leaveBattlefield(owner: Seat, victim: Perm, byOpponent: Seat?, by: SimCard) {
        owner.creatures.remove(victim); owner.others.remove(victim); owner.rocks.remove(victim)
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
        if (victim.card.isCommander && !victim.token) {
            owner.track.cmdRemoved++
            owner.commandZone += victim.card
        }
    }

    private fun burn(p: Seat, c: SimCard, t: Int) {
        val amount = c.damage.coerceAtLeast(2)
        val target = biggestThreat(p) ?: return
        val creature = target.creatures.filter { it.toughness <= amount }.maxByOrNull { it.power }
        if (creature != null && creature.power >= 3 && Role.REMOVAL in c.roles) {
            p.track.dmgCreatures += amount
            leaveBattlefield(target, creature, byOpponent = p, by = c)
        } else {
            damagePlayer(p, target, amount, c, combat = false, t = t, cause = EndCondition.NONCOMBAT_DAMAGE)
        }
    }

    private fun drain(p: Seat, c: SimCard, t: Int) {
        val amount = c.damage.coerceAtLeast(1)
        val targets = opponents(p)
        for (q in targets) damagePlayer(p, q, amount, c, combat = false, t = t, cause = EndCondition.LIFE_LOSS)
        p.life += amount * targets.size
        p.track.lifeGained += amount * targets.size
    }

    private fun damagePlayer(src: Seat, dst: Seat, amount: Int, card: SimCard, combat: Boolean, t: Int, cause: EndCondition) {
        if (!dst.alive || amount <= 0) return
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
        if (combat && card.isCommander) {
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

    private fun combat(p: Seat, t: Int) {
        val attackers = p.creatures.filter { !it.sick && it.power > 0 && !(it.card.isManaSource && it.power <= 1) }
        if (attackers.isEmpty()) return
        val defender = opponents(p).minByOrNull { it.life + it.boardPower + rng.nextInt(12) } ?: return
        // Don't swing into a much bigger board unless the attackers fly.
        val evasive = attackers.count { it.card.flying || it.card.unblockable }
        if (attackers.sumOf { it.power } * 2 < defender.boardPower && evasive == 0 && rng.nextFloat() < 0.6f) return
        p.track.attacks++
        p.track.attackersTotal += attackers.size
        val blockers = defender.creatures.filter { !(it.card.isManaSource && it.power <= 1) || defender.life < 15 }.toMutableList()
        var incoming = attackers.sumOf { it.power }
        for (a in attackers.sortedByDescending { it.power }) {
            if (!defender.alive) break
            val canBlock = blockers.filter { b -> !a.card.unblockable && (!a.card.flying || b.card.flying || b.card.reach) }
                .takeIf { !a.card.menace || it.size >= 2 }.orEmpty()
            // Block when the blocker survives, trades with a bigger creature, or the defender would die otherwise.
            val danger = defender.life <= incoming
            val blocker = canBlock.filter { b ->
                (b.toughness > a.power && !a.card.deathtouch) || (b.power >= a.toughness && b.card.mv < a.card.mv) || danger
            }.maxByOrNull { it.toughness }
            incoming -= a.power
            if (blocker != null && (danger || rng.nextFloat() < 0.6f)) {
                blockers.remove(blocker)
                defender.track.blocks++
                p.track.dmgCreatures += a.power
                defender.track.dmgCreatures += blocker.power
                val strikes = if (a.card.doubleStrike) 2 else 1
                val blockerDies = a.power * strikes >= blocker.toughness || (a.card.deathtouch && a.power > 0)
                // A double striker that kills the blocker with first-strike damage takes none back.
                val attackerDies = !(a.card.doubleStrike && a.power >= blocker.toughness) &&
                    (blocker.power >= a.toughness || (blocker.card.deathtouch && blocker.power > 0))
                if (a.card.lifelink) { p.life += a.power; p.track.lifeGained += a.power }
                if (a.card.trample && a.power > blocker.toughness) {
                    damagePlayer(p, defender, a.power - blocker.toughness, a.card, combat = true, t = t, cause = EndCondition.COMBAT_DAMAGE)
                }
                if (blockerDies) { defender.creatures.remove(blocker); defender.track.creaturesLost++; if (blocker.card.isCommander && !blocker.token) defender.commandZone += blocker.card }
                if (attackerDies) { p.creatures.remove(a); p.track.creaturesLost++; if (a.card.isCommander && !a.token) p.commandZone += a.card }
            } else {
                val dmg = a.power * if (a.card.doubleStrike) 2 else 1
                if (a.card.lifelink) { p.life += dmg; p.track.lifeGained += dmg }
                damagePlayer(p, defender, dmg, a.card, combat = true, t = t, cause = EndCondition.COMBAT_DAMAGE)
            }
        }
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
    }
    override fun play(seats: List<Deck>, seed: Long): GameResult =
        QuickGame(seats.map { prepared[it.id] ?: QuickDeck.from(it) }, Random(seed)).play()
}
