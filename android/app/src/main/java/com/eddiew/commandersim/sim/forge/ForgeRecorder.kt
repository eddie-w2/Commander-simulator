package com.eddiew.commandersim.sim.forge

import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.EndCondition
import com.eddiew.commandersim.sim.GameResult
import com.eddiew.commandersim.sim.MAX_TURNS
import com.eddiew.commandersim.sim.PlayerTrack
import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.GameEndReason
import forge.game.card.Card
import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.event.GameEvent
import forge.game.event.GameEventAttackersDeclared
import forge.game.event.GameEventBlockersDeclared
import forge.game.event.GameEventCardChangeZone
import forge.game.event.GameEventCardCounters
import forge.game.event.GameEventCardDamaged
import forge.game.event.GameEventLandPlayed
import forge.game.event.GameEventMulligan
import forge.game.event.GameEventPlayerDamaged
import forge.game.event.GameEventPlayerLivesChanged
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventSpellResolved
import forge.game.event.GameEventTurnBegan
import forge.game.phase.PhaseType
import forge.game.player.GameLossReason
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.zone.ZoneType

/**
 * Listens to one Forge game's event bus and fills a [PlayerTrack] per seat, exactly like the sample
 * model does, so every existing statistic and dashboard works on real Forge games.
 *
 * Per-turn series are indexed by each player's own turn count (turn 1 = their first turn).
 */
internal class ForgeRecorder(
    private val game: Game,
    /** Per seat: Forge card name (lowercase) → the app's card name. */
    private val nameMaps: List<Map<String, String>>,
    private val maxRounds: Int,
    private val maxMillis: Long,
    /** Called at the start of each round with the round number, for progress display. */
    private val onRound: (Int) -> Unit = {},
) {
    /** Players in registration order, i.e. the order of the decks passed in. */
    private val players: List<Player> = game.registeredPlayers.toList()
    private val seatById: Map<Int, Int> = players.withIndex().associate { (i, p) -> p.id to i }
    private val n = players.size
    private val tracks = List(n) { PlayerTrack(n) }
    private val turnsTaken = IntArray(n)
    private val started = BooleanArray(n)
    private val eliminated = BooleanArray(n)
    private val turnOrder = mutableListOf<Int>()
    private val startTime = System.currentTimeMillis()
    private var lastCause: EndCondition? = null
    private var forcedDraw = false

    /** Last damage dealt to each seat: card name, source seat, combat, commander. */
    private class Hit(val card: String, val seat: Int, val combat: Boolean, val commander: Boolean)
    private val lastHit = arrayOfNulls<Hit>(n)

    /** Removal done by one resolving spell/ability, for board-wipe detection. */
    private var resolvingKey: Int = 0
    private var resolvingRemovedOthers = 0
    private var resolvingRemovedTotal = 0
    private val removalSpellsSeen = HashSet<Int>()

    private fun seat(p: PlayerView?): Int? = p?.let { seatById[it.id] }
    private fun seat(p: Player?): Int? = p?.let { seatById[it.id] }
    private fun turnIdx(s: Int) = (turnsTaken[s] - 1).coerceIn(0, MAX_TURNS - 1)

    /** Maps a Forge card name to the deck's own name for that card. */
    private fun appName(s: Int?, forgeName: String?): String {
        val name = forgeName ?: "?"
        return s?.let { nameMaps.getOrNull(it)?.get(name.lowercase()) } ?: name
    }

    private fun card(view: CardView?): Card? = view?.let { runCatching { game.findById(it.id) }.getOrNull() }

    /** The printed card name (front face for double-faced cards), falling back to what's displayed. */
    private fun nameOf(view: CardView?): String? {
        val c = card(view)
        return c?.paperCard?.name ?: c?.name ?: view?.currentState?.name
    }
    private fun nameOf(c: Card?): String? = c?.paperCard?.name ?: c?.name

    private fun cardType(c: Card?): CardType = when {
        c == null -> CardType.UNKNOWN
        c.isLand -> CardType.LAND
        c.isCreature -> CardType.CREATURE
        c.isPlaneswalker -> CardType.PLANESWALKER
        c.isInstant -> CardType.INSTANT
        c.isSorcery -> CardType.SORCERY
        c.isArtifact -> CardType.ARTIFACT
        c.isEnchantment -> CardType.ENCHANTMENT
        c.isBattle -> CardType.BATTLE
        else -> CardType.UNKNOWN
    }

    /** Diagnostics: events seen by type, and the first few errors swallowed while recording. */
    val eventCounts = HashMap<String, Int>()
    val errors = mutableListOf<String>()

    /** Life totals per seat right now. */
    fun lifeTotals(): List<Int> = players.map { it.life }

    @Subscribe
    fun receive(evt: GameEvent) {
        eventCounts.merge(evt.javaClass.simpleName, 1, Int::plus)
        try {
            when (evt) {
                is GameEventTurnBegan -> onTurn(evt)
                is GameEventPlayerDamaged -> onPlayerDamaged(evt)
                is GameEventCardDamaged -> {
                    val s = seat(evt.source()?.controller) ?: return
                    tracks[s].dmgCreatures += evt.amount()
                }
                is GameEventPlayerLivesChanged -> {
                    val s = seat(evt.player()) ?: return
                    val delta = evt.newLives() - evt.oldLives()
                    if (delta > 0) tracks[s].lifeGained += delta
                }
                is GameEventSpellAbilityCast -> onCast(evt)
                is GameEventSpellResolved -> onResolved(evt)
                is GameEventLandPlayed -> {
                    val s = seat(evt.player()) ?: return
                    val t = tracks[s]
                    t.landsPlayed++
                    t.landByTurn[turnIdx(s)] = true
                    noteCast(s, appName(s, nameOf(evt.land())), turnIdx(s))
                }
                is GameEventCardChangeZone -> onZoneChange(evt)
                is GameEventMulligan -> seat(evt.player())?.let { tracks[it].mulligans++ }
                is GameEventAttackersDeclared -> {
                    val s = seat(evt.player()) ?: return
                    val count = evt.attackersMap().size()
                    if (count > 0) { tracks[s].attacks++; tracks[s].attackersTotal += count }
                }
                is GameEventBlockersDeclared -> {
                    val s = seat(evt.defendingPlayer()) ?: return
                    tracks[s].blocks += evt.blockers().values.sumOf { it.size() }
                }
                is GameEventCardCounters -> {
                    val c = evt.card() ?: return
                    if (evt.type()?.`is`(CounterEnumType.P1P1) == true && evt.newValue() > evt.oldValue()) {
                        seat(c.controller)?.let { tracks[it].counterPlaced += evt.newValue() - evt.oldValue() }
                    }
                }
                else -> Unit
            }
        } catch (e: RuntimeException) {
            // A stat we couldn't read must never break the game itself.
            if (errors.size < 5) errors += "${evt.javaClass.simpleName}: $e"
        }
        checkEliminations()
        checkLimits()
    }

    private fun onTurn(evt: GameEventTurnBegan) {
        val s = seat(evt.turnOwner()) ?: return
        if (s !in turnOrder) turnOrder += s
        val p = players[s]
        val t = tracks[s]
        if (!started[s]) {
            // Opening hand = what's in hand when the player's first turn starts.
            started[s] = true
            for (c in p.getCardsIn(ZoneType.Hand)) {
                val ct = t.card(appName(s, nameOf(c)))
                ct.seen = true; ct.inOpening = true; ct.drawnRound = 0
            }
        }
        turnsTaken[s]++
        t.turnsTaken = turnsTaken[s]
        if (turnOrder.firstOrNull() == s) runCatching { onRound(turnsTaken[s]) }
        val i = turnIdx(s)
        val hand = p.getCardsIn(ZoneType.Hand).size
        val battlefield = p.getCardsIn(ZoneType.Battlefield)
        t.handByTurn[i] = hand
        t.maxHand = maxOf(t.maxHand, hand)
        t.permsByTurn[i] = battlefield.size
        t.creaturesByTurn[i] = battlefield.count { it.isCreature }
        t.landsInPlayByTurn[i] = battlefield.count { it.isLand }
        t.lifeByTurn[i] = p.life
    }

    private fun onPlayerDamaged(evt: GameEventPlayerDamaged) {
        val dst = seat(evt.target()) ?: return
        val srcView = evt.source()
        val src = seat(srcView?.controller) ?: return
        val amount = evt.amount()
        if (amount <= 0) return
        val name = appName(src, nameOf(srcView))
        val commander = srcView?.isCommander == true
        val t = turnIdx(src)
        val s = tracks[src]
        val d = tracks[dst]
        d.taken += amount
        d.takenByTurn[turnIdx(dst)] += amount
        d.takenBySeat[src] += amount
        d.takenByCard.merge("$name|$src", amount, Int::plus)
        s.dmgPlayers += amount
        if (evt.combat()) { s.dmgCombat += amount; d.takenCombat += amount } else { s.dmgNonCombat += amount; d.takenNonCombat += amount }
        if (evt.combat() && commander) { s.dmgCommander += amount; d.takenCommander += amount }
        s.dmgByTurn[t] += amount
        s.dmgBySeat[dst] += amount
        val ct = s.card(name)
        ct.damage += amount
        if (evt.combat()) ct.combatDamage += amount else ct.nonCombatDamage += amount
        (ct.dmgBySeat ?: IntArray(n).also { ct.dmgBySeat = it })[dst] += amount
        (ct.dmgByRound ?: IntArray(MAX_TURNS).also { ct.dmgByRound = it })[t] += amount
        lastHit[dst] = Hit(name, src, evt.combat(), commander)
    }

    private fun noteCast(s: Int, name: String, t: Int) {
        val ct = tracks[s].card(name)
        ct.casts++
        if (ct.firstCastRound < 0) ct.firstCastRound = t + 1
    }

    private fun onCast(evt: GameEventSpellAbilityCast) {
        val sa = evt.sa() ?: return
        if (!sa.isSpell) return
        val host = sa.hostCard ?: return
        val s = seat(host.controller) ?: return
        val i = turnIdx(s)
        val name = appName(s, nameOf(host))
        noteCast(s, name, i)
        val c = card(host)
        val mv = c?.cmc ?: 0
        tracks[s].manaByTurn[i] += mv
        if (host.isCommander) {
            val t = tracks[s]
            t.cmdCasts++
            if (t.cmdFirstCast < 0) t.cmdFirstCast = i + 1
        }
    }

    private fun onResolved(evt: GameEventSpellResolved) {
        val sa = evt.spell() ?: return
        val host = sa.hostCard ?: return
        val s = seat(host.controller) ?: return
        if (evt.hasFizzled()) {
            tracks[s].card(appName(s, nameOf(host))).countered++
        }
        // Board wipe: one resolution removed 3+ permanents, 2+ of them other players'.
        if (resolvingRemovedTotal >= 3 && resolvingRemovedOthers >= 2) tracks[s].wipes++
        resolvingKey = 0
        resolvingRemovedOthers = 0
        resolvingRemovedTotal = 0
    }

    private fun onZoneChange(evt: GameEventCardChangeZone) {
        val view = evt.card() ?: return
        val from = evt.from()?.zoneType()
        val to = evt.to()?.zoneType()
        val ownerSeat = seat(view.owner) ?: seat(view.controller) ?: return
        val t = tracks[ownerSeat]
        val name = appName(ownerSeat, nameOf(view))
        val c = card(view)

        when {
            // Draws (and tutors) once the game is under way.
            from == ZoneType.Library && to == ZoneType.Hand && started[ownerSeat] -> {
                t.drawn++
                t.drawsByTurn[turnIdx(ownerSeat)]++
                val ct = t.card(name)
                if (!ct.seen) { ct.seen = true; ct.drawnRound = turnIdx(ownerSeat) }
                t.maxHand = maxOf(t.maxHand, players[ownerSeat].getCardsIn(ZoneType.Hand).size)
            }
            from == ZoneType.Library && to == ZoneType.Graveyard -> t.milled++
            from == ZoneType.Hand && to == ZoneType.Graveyard && started[ownerSeat] -> t.discarded++
            (from == null || from == ZoneType.None) && to == ZoneType.Battlefield && view.isToken -> {
                seat(view.controller)?.let { tracks[it].tokens++ }
            }
            from == ZoneType.Battlefield && to != ZoneType.Battlefield -> onLeftBattlefield(view, c, ownerSeat, name)
            from == ZoneType.Stack && (to == ZoneType.Graveyard || to == ZoneType.Exile) -> {
                // Countered: a spell left the stack while an opponent's spell or ability resolved.
                val (rSeat, rName) = resolver() ?: return
                if (rSeat != ownerSeat) {
                    t.card(name).countered++
                    tracks[rSeat].counters++
                    tracks[rSeat].card(rName).removals++
                }
            }
        }
    }

    private fun onLeftBattlefield(view: CardView, c: Card?, victim: Int, name: String) {
        val t = tracks[victim]
        val phase = runCatching { game.phaseHandler.phase }.getOrNull()
        val inCombat = phase == PhaseType.COMBAT_DAMAGE || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
        if (inCombat && c?.isCreature == true) t.creaturesLost++
        if (view.isCommander) {
            t.cmdRemoved++
        }
        val (rSeat, rName) = resolver() ?: return
        val key = runCatching { System.identityHashCode(game.stack.peekAbility()) }.getOrDefault(0)
        if (key != resolvingKey) { resolvingKey = key; resolvingRemovedOthers = 0; resolvingRemovedTotal = 0 }
        resolvingRemovedTotal++
        if (rSeat == victim) {
            t.sacrificed++
            return
        }
        resolvingRemovedOthers++
        val r = tracks[rSeat]
        r.permsRemoved++
        r.removedByType.merge(cardType(c), 1, Int::plus)
        r.removalAtSeat[victim]++
        r.card(rName).removals++
        if (removalSpellsSeen.add(key)) r.removalSpells++
        t.received++
        if (!view.isToken) {
            val ct = t.card(name)
            ct.removed++
            ct.removedBy.merge("$rName|$rSeat", 1, Int::plus)
            t.ownRemoved.merge(name, 1, Int::plus)
        }
    }

    /** Seat and card name of the spell or ability currently resolving, if any. */
    private fun resolver(): Pair<Int, String>? {
        val stack = game.stack
        if (!stack.isResolving) return null
        val sa = runCatching { stack.peekAbility() }.getOrNull() ?: return null
        val s = seat(sa.activatingPlayer) ?: return null
        return s to appName(s, nameOf(sa.hostCard))
    }

    private fun checkEliminations() {
        val alive = players.indices.count { !eliminated[it] && !players[it].hasLost() }
        for (i in players.indices) {
            if (eliminated[i] || !players[i].hasLost()) continue
            eliminated[i] = true
            val t = tracks[i]
            t.place = alive + 1
            t.eliminatedTurn = maxOf(1, turnsTaken.maxOrNull() ?: 1)
            val reason = runCatching { players[i].outcome?.lossState }.getOrNull()
            val hit = lastHit[i]
            val cause = when (reason) {
                GameLossReason.CommanderDamage -> EndCondition.COMMANDER_DAMAGE
                GameLossReason.Milled -> EndCondition.MILL
                GameLossReason.Poisoned -> EndCondition.POISON
                GameLossReason.SpellEffect, GameLossReason.OpponentWon -> EndCondition.ALT_WIN
                GameLossReason.LifeReachedZero -> when {
                    hit == null -> EndCondition.LIFE_LOSS
                    hit.combat && hit.commander -> EndCondition.COMBAT_DAMAGE
                    hit.combat -> EndCondition.COMBAT_DAMAGE
                    else -> EndCondition.NONCOMBAT_DAMAGE
                }
                else -> EndCondition.TIMEOUT
            }
            t.lossCause = cause
            lastCause = cause
            if (hit != null && (reason == GameLossReason.LifeReachedZero || reason == GameLossReason.CommanderDamage)) {
                t.lethalBy = "${hit.card}|${hit.seat}"
                tracks[hit.seat].kills++
                tracks[hit.seat].card(hit.card).killShots++
            }
        }
    }

    private fun checkLimits() {
        if (forcedDraw || game.isGameOver) return
        val tooLong = (turnsTaken.maxOrNull() ?: 0) > maxRounds
        val tooSlow = System.currentTimeMillis() - startTime > maxMillis
        if (tooLong || tooSlow) {
            forcedDraw = true
            game.setGameOver(GameEndReason.Draw)
        }
    }

    /** Builds the result once the game has ended (or been stopped). */
    fun finish(timedOut: Boolean): GameResult {
        checkEliminations()
        val outcome = runCatching { game.outcome }.getOrNull()
        val winner = if (timedOut || forcedDraw || outcome == null || outcome.isDraw) null
        else players.indices.firstOrNull { !players[it].hasLost() }
        val rounds = (turnsTaken.maxOrNull() ?: 1).coerceAtLeast(1)
        if (winner != null) {
            tracks[winner].place = 1
        } else {
            players.indices.filter { !eliminated[it] }.sortedByDescending { players[it].life }
                .forEachIndexed { rank, i -> tracks[i].place = rank + 1 }
        }
        // Eliminated players are at 0 life for the rest of the game.
        for (i in players.indices) {
            if (!eliminated[i]) continue
            for (r in 0 until rounds.coerceAtMost(MAX_TURNS)) if (tracks[i].lifeByTurn[r] < 0) tracks[i].lifeByTurn[r] = 0
        }
        val position = IntArray(n) { s -> turnOrder.indexOf(s).let { if (it < 0) s else it } }
        return GameResult(
            winnerSeat = winner,
            winCondition = if (winner != null) lastCause ?: EndCondition.ALT_WIN else EndCondition.TIMEOUT,
            rounds = rounds.coerceAtMost(MAX_TURNS),
            tracks = tracks,
            turnPosition = position,
        )
    }
}
