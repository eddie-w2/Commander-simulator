package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.BoardStats
import com.eddiew.commandersim.data.model.CardStat
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.CardVerdict
import com.eddiew.commandersim.data.model.CombatStats
import com.eddiew.commandersim.data.model.CommanderStats
import com.eddiew.commandersim.data.model.ConditionCount
import com.eddiew.commandersim.data.model.DamageStats
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.EndCondition
import com.eddiew.commandersim.data.model.Insight
import com.eddiew.commandersim.data.model.InsightKind
import com.eddiew.commandersim.data.model.LethalSource
import com.eddiew.commandersim.data.model.LifeStats
import com.eddiew.commandersim.data.model.NamedValue
import com.eddiew.commandersim.data.model.NemesisStat
import com.eddiew.commandersim.data.model.OutcomeStats
import com.eddiew.commandersim.data.model.RemovalStats
import com.eddiew.commandersim.data.model.ResourceStats
import com.eddiew.commandersim.data.model.SourceDamage
import com.eddiew.commandersim.data.model.SplitScalar
import com.eddiew.commandersim.data.model.SplitSeries
import com.eddiew.commandersim.data.model.ThreatStats
import com.eddiew.commandersim.data.model.WinLossSplit
import kotlin.math.abs

/** Running per-turn average. */
private class TurnSeries {
    val sum = DoubleArray(MAX_TURNS)
    val n = IntArray(MAX_TURNS)
    fun add(i: Int, v: Double) { sum[i] += v; n[i]++ }
    fun mean(): List<Double> = List(MAX_TURNS) { if (n[it] > 0) sum[it] / n[it] else 0.0 }
}

/** The same series kept separately for won and lost games. */
private class SplitTurnSeries {
    val wins = TurnSeries()
    val losses = TurnSeries()
    fun add(won: Boolean, i: Int, v: Double) = (if (won) wins else losses).add(i, v)
}

private class CardAcc(val type: CardType, val mv: Double?) {
    var seenGames = 0; var seenWins = 0
    var openGames = 0; var openWins = 0
    var castGames = 0; var castWins = 0
    var deadGames = 0
    var casts = 0
    var castTurnSum = 0
    var heldSum = 0; var heldN = 0
    val castTurn = IntArray(MAX_TURNS)
    val castTurnWins = IntArray(MAX_TURNS)
    var countered = 0
    var damage = 0L; var combat = 0L; var nonCombat = 0L
    var killShots = 0
    var removals = 0
    var removed = 0
    val dmgToDeck = HashMap<String, Long>()
    val removedBy = HashMap<String, Int>()
    val dmgByRound = LongArray(MAX_TURNS)
}

/** Accumulates every game one deck played and builds its [DeckReport]. */
internal class DeckAccumulator(val deck: Deck) {
    private var games = 0
    private var wins = 0
    private var draws = 0
    private val placements = IntArray(4)
    private var elimTurnSum = 0
    private var elimCount = 0
    private val winTurns = IntArray(MAX_TURNS)
    private val winCond = HashMap<EndCondition, Int>()
    private val lossCause = HashMap<EndCondition, Int>()
    private val seatGames = IntArray(4)
    private val seatWins = IntArray(4)

    private var dmgPlayers = 0L; private var dmgCombat = 0L; private var dmgNonCombat = 0L; private var dmgCmd = 0L
    private var dmgCreatures = 0L; private var taken = 0L; private var takenCombat = 0L; private var takenNonCombat = 0L
    private var takenCmd = 0L; private var lifeGained = 0L; private var kills = 0L
    private val dmgByTurn = TurnSeries()
    private val takenByTurn = TurnSeries()
    private val dmgByTarget = HashMap<String, Double>()
    private val takenByDeck = HashMap<String, Double>()
    private val gamesWith = HashMap<String, Int>()
    private val takenByCard = HashMap<String, Long>()
    private val lethal = HashMap<String, Int>()

    private val cards = LinkedHashMap<String, CardAcc>()

    private var removalSpells = 0L; private var permsRemoved = 0L; private var received = 0L; private var counters = 0L
    private var wipes = 0L; private var sacrificed = 0L
    private val removedByType = HashMap<CardType, Long>()
    private val removalAt = HashMap<String, Double>()
    private val ownRemoved = HashMap<String, Long>()

    private var drawn = 0L; private var maxHandSum = 0L; private var discarded = 0L; private var landsPlayed = 0L
    private var mulliganGames = 0; private var mulligans = 0L; private var milled = 0L
    private val drawsByTurn = TurnSeries()
    private val handByTurn = TurnSeries()
    private val landsInPlay = TurnSeries()
    private val landHit = TurnSeries()
    private val manaByTurn = TurnSeries()
    private val permsByTurn = TurnSeries()
    private val creaturesByTurn = TurnSeries()

    private val life = SplitTurnSeries()
    private val lifeAll = TurnSeries()
    private val oppLife = TurnSeries()
    private val splitDamage = SplitTurnSeries()
    private val splitHand = SplitTurnSeries()
    private val splitLands = SplitTurnSeries()
    private val splitBoard = SplitTurnSeries()
    private val splitMana = SplitTurnSeries()

    /** Per-game scalars kept separately for wins [0] and losses [1]. */
    private val scalarNames = listOf(
        "Cards drawn", "Removal cast", "Opposing permanents removed", "Own permanents lost", "Damage dealt",
        "Damage taken", "Mulligans", "Commander first cast (round)", "Lands played", "Counterspells",
    )
    private val scalarHigherBetter = listOf(true, true, true, false, true, false, false, false, true, true)
    private val scalarSum = Array(2) { DoubleArray(scalarNames.size) }
    private val scalarN = Array(2) { IntArray(scalarNames.size) }

    private var cmdCasts = 0L; private var cmdFirstSum = 0L; private var cmdFirstN = 0; private var cmdTax = 0L; private var cmdRemoved = 0L
    private var cmdEarlyGames = 0; private var cmdEarlyWins = 0; private var cmdNeverGames = 0; private var cmdNeverWins = 0

    private var attacks = 0L; private var attackers = 0L; private var blocks = 0L; private var lost = 0L
    private var tokens = 0L; private var countersPlaced = 0L

    // Opposing cards cast in this deck's games: "card|deckId" -> [games, wins]
    private val faced = HashMap<String, IntArray>()

    init {
        (deck.commanders + deck.cards).forEach { c -> cards[c.name] = CardAcc(c.type, c.manaValue) }
    }

    fun add(r: GameResult, seat: Int, seatDeckIds: List<String>) {
        val t = r.tracks[seat]
        val won = r.winnerSeat == seat
        val lostGame = !won && !(r.winnerSeat == null && t.eliminatedTurn < 0)
        games++
        if (won) {
            wins++
            winTurns[(r.rounds - 1).coerceIn(0, MAX_TURNS - 1)]++
            r.winCondition?.let { winCond.merge(it, 1, Int::plus) }
        }
        if (r.winnerSeat == null && t.eliminatedTurn < 0) draws++
        if (t.place in 1..4) placements[t.place - 1]++
        if (t.eliminatedTurn > 0) { elimTurnSum += t.eliminatedTurn; elimCount++ }
        t.lossCause?.let { lossCause.merge(it, 1, Int::plus) }
        if (r.winnerSeat == null && t.eliminatedTurn < 0) lossCause.merge(EndCondition.TIMEOUT, 1, Int::plus)
        val position = r.turnPosition?.getOrNull(seat) ?: seat
        if (position < 4) { seatGames[position]++; if (won) seatWins[position]++ }

        // ── Damage
        dmgPlayers += t.dmgPlayers; dmgCombat += t.dmgCombat; dmgNonCombat += t.dmgNonCombat
        dmgCmd += t.dmgCommander; dmgCreatures += t.dmgCreatures; taken += t.taken; takenCombat += t.takenCombat
        takenNonCombat += t.takenNonCombat; takenCmd += t.takenCommander; lifeGained += t.lifeGained; kills += t.kills
        for (i in 0 until t.turnsTaken.coerceAtMost(MAX_TURNS)) {
            dmgByTurn.add(i, t.dmgByTurn[i].toDouble())
            takenByTurn.add(i, t.takenByTurn[i].toDouble())
        }
        seatDeckIds.forEachIndexed { s, id ->
            if (s != seat) {
                dmgByTarget.merge(id, t.dmgBySeat[s].toDouble(), Double::plus)
                takenByDeck.merge(id, t.takenBySeat[s].toDouble(), Double::plus)
                removalAt.merge(id, t.removalAtSeat[s].toDouble(), Double::plus)
                gamesWith.merge(id, 1, Int::plus)
            }
        }
        fun relabel(key: String): String {
            val card = key.substringBeforeLast("|")
            val s = key.substringAfterLast("|").toIntOrNull() ?: return key
            return "$card|${seatDeckIds.getOrElse(s) { "?" }}"
        }
        t.takenByCard.forEach { (k, v) -> takenByCard.merge(relabel(k), v.toLong(), Long::plus) }
        t.lethalBy?.let { lethal.merge(relabel(it), 1, Int::plus) }

        // ── Cards
        for ((name, a) in cards) {
            val ct = t.cards[name]
            val cast = ct != null && ct.casts > 0
            val seen = ct?.seen == true || cast
            if (seen) { a.seenGames++; if (won) a.seenWins++ }
            if (ct?.inOpening == true) { a.openGames++; if (won) a.openWins++ }
            if (cast) {
                ct!!
                a.castGames++; a.casts += ct.casts; a.castTurnSum += ct.firstCastRound
                val idx = (ct.firstCastRound - 1).coerceIn(0, MAX_TURNS - 1)
                a.castTurn[idx]++
                if (won) { a.castWins++; a.castTurnWins[idx]++ }
                if (ct.drawnRound >= 0) { a.heldSum += (ct.firstCastRound - 1 - ct.drawnRound).coerceAtLeast(0); a.heldN++ }
            } else if (seen) {
                a.deadGames++
            }
            if (ct != null) {
                a.countered += ct.countered
                a.damage += ct.damage; a.combat += ct.combatDamage; a.nonCombat += ct.nonCombatDamage
                a.killShots += ct.killShots
                a.removals += ct.removals; a.removed += ct.removed
                ct.dmgBySeat?.forEachIndexed { s, v -> if (v > 0) a.dmgToDeck.merge(seatDeckIds[s], v.toLong(), Long::plus) }
                ct.dmgByRound?.forEachIndexed { i, v -> a.dmgByRound[i] += v.toLong() }
                ct.removedBy.forEach { (k, v) -> a.removedBy.merge(relabel(k), v, Int::plus) }
            }
        }

        // ── Opposing cards faced
        r.tracks.forEachIndexed { s, other ->
            if (s == seat) return@forEachIndexed
            val oppDeck = seatDeckIds[s]
            for ((name, ct) in other.cards) {
                if (ct.casts == 0) continue
                val stat = faced.getOrPut("$name|$oppDeck") { IntArray(2) }
                stat[0]++; if (won) stat[1]++
            }
        }

        // ── Removal
        removalSpells += t.removalSpells; permsRemoved += t.permsRemoved; received += t.received
        counters += t.counters; wipes += t.wipes; sacrificed += t.sacrificed
        t.removedByType.forEach { (k, v) -> removedByType.merge(k, v.toLong(), Long::plus) }
        t.ownRemoved.forEach { (k, v) -> ownRemoved.merge(k, v.toLong(), Long::plus) }

        // ── Resources, board, life
        drawn += t.drawn; maxHandSum += t.maxHand; discarded += t.discarded; landsPlayed += t.landsPlayed
        if (t.mulligans > 0) mulliganGames++
        mulligans += t.mulligans; milled += t.milled
        for (i in 0 until MAX_TURNS) {
            if (i < t.turnsTaken) {
                drawsByTurn.add(i, t.drawsByTurn[i].toDouble())
                landHit.add(i, if (t.landByTurn[i]) 1.0 else 0.0)
                manaByTurn.add(i, t.manaByTurn[i].toDouble())
                if (won || lostGame) {
                    splitDamage.add(won, i, t.dmgByTurn[i].toDouble())
                    splitMana.add(won, i, t.manaByTurn[i].toDouble())
                }
            }
            if (t.handByTurn[i] >= 0) {
                handByTurn.add(i, t.handByTurn[i].toDouble())
                if (won || lostGame) splitHand.add(won, i, t.handByTurn[i].toDouble())
            }
            if (t.permsByTurn[i] >= 0) {
                permsByTurn.add(i, t.permsByTurn[i].toDouble())
                creaturesByTurn.add(i, t.creaturesByTurn[i].toDouble())
                if (won || lostGame) splitBoard.add(won, i, t.permsByTurn[i].toDouble())
            }
            if (t.landsInPlayByTurn[i] >= 0) {
                landsInPlay.add(i, t.landsInPlayByTurn[i].toDouble())
                if (won || lostGame) splitLands.add(won, i, t.landsInPlayByTurn[i].toDouble())
            }
            if (t.lifeByTurn[i] >= 0) {
                lifeAll.add(i, t.lifeByTurn[i].toDouble())
                if (won || lostGame) life.add(won, i, t.lifeByTurn[i].toDouble())
                val others = r.tracks.filterIndexed { s, _ -> s != seat }.map { it.lifeByTurn[i] }.filter { it >= 0 }
                if (others.isNotEmpty()) oppLife.add(i, others.average())
            }
        }

        if (won || lostGame) {
            val k = if (won) 0 else 1
            fun sc(i: Int, v: Double) { scalarSum[k][i] += v; scalarN[k][i]++ }
            sc(0, t.drawn.toDouble()); sc(1, t.removalSpells.toDouble()); sc(2, t.permsRemoved.toDouble())
            sc(3, t.received.toDouble()); sc(4, t.dmgPlayers.toDouble()); sc(5, t.taken.toDouble())
            sc(6, t.mulligans.toDouble()); if (t.cmdFirstCast > 0) sc(7, t.cmdFirstCast.toDouble())
            sc(8, t.landsPlayed.toDouble()); sc(9, t.counters.toDouble())
        }

        // ── Commander
        cmdCasts += t.cmdCasts
        if (t.cmdFirstCast > 0) { cmdFirstSum += t.cmdFirstCast; cmdFirstN++ } else { cmdNeverGames++; if (won) cmdNeverWins++ }
        cmdTax += 2L * (0 until t.cmdCasts).sum()
        cmdRemoved += t.cmdRemoved
        if (t.cmdFirstCast in 1..5) { cmdEarlyGames++; if (won) cmdEarlyWins++ }

        attacks += t.attacks; attackers += t.attackersTotal; blocks += t.blocks; lost += t.creaturesLost
        tokens += t.tokens; countersPlaced += t.counterPlaced
    }

    private fun per(x: Long) = x.toDouble() / games.coerceAtLeast(1)
    private fun rate(w: Int, n: Int, min: Int = 5): Double? = if (n >= min) w.toDouble() / n else null
    private fun <K> topLong(map: Map<K, Long>, k: Int, label: (K) -> String) =
        map.entries.sortedByDescending { it.value }.take(k).map { NamedValue(label(it.key), it.value.toDouble() / games) }

    fun build(deckNames: Map<String, String>): DeckReport {
        val (lo, hi) = SampleSimulator.wilson(wins, games)
        val winRate = wins.toDouble() / games.coerceAtLeast(1)
        val avgPlace = placements.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / games.coerceAtLeast(1)
        val outcome = OutcomeStats(
            games = games, wins = wins, draws = draws,
            winRate = winRate, ci95Low = lo, ci95High = hi,
            placements = placements.toList(), avgPlace = avgPlace,
            avgEliminationTurn = if (elimCount > 0) elimTurnSum.toDouble() / elimCount else null,
            winTurnHistogram = winTurns.toList(),
            winConditions = winCond.map { ConditionCount(it.key, it.value) }.sortedByDescending { it.count },
            lossCauses = lossCause.map { ConditionCount(it.key, it.value) }.sortedByDescending { it.count },
            seatWinRate = List(4) { if (seatGames[it] > 0) seatWins[it].toDouble() / seatGames[it] else 0.0 },
        )

        val cardStats = cards.map { (name, a) ->
            CardStat(
                name = name, type = a.type, manaValue = a.mv, games = games,
                gamesSeen = a.seenGames, gamesCast = a.castGames,
                seenRate = a.seenGames.toDouble() / games,
                openingHandRate = a.openGames.toDouble() / games,
                castRate = a.castGames.toDouble() / games,
                avgCastTurn = if (a.castGames > 0) a.castTurnSum.toDouble() / a.castGames else null,
                castsPerGame = a.casts.toDouble() / games,
                deadRate = if (a.seenGames > 0) a.deadGames.toDouble() / a.seenGames else 0.0,
                counteredRate = if (a.casts > 0) a.countered.toDouble() / a.casts else 0.0,
                avgTurnsInHand = if (a.heldN > 0) a.heldSum.toDouble() / a.heldN else null,
                winRateWhenSeen = rate(a.seenWins, a.seenGames),
                winRateWhenNotSeen = rate(wins - a.seenWins, games - a.seenGames),
                winRateWhenCast = rate(a.castWins, a.castGames),
                winRateWhenNotCast = rate(wins - a.castWins, games - a.castGames),
                winRateInOpeningHand = rate(a.openWins, a.openGames),
                castTurnHistogram = a.castTurn.toList(),
                winRateByCastTurn = List(MAX_TURNS) { rate(a.castTurnWins[it], a.castTurn[it]) },
                damagePerGame = a.damage.toDouble() / games,
                combatDamagePerGame = a.combat.toDouble() / games,
                nonCombatDamagePerGame = a.nonCombat.toDouble() / games,
                killShots = a.killShots,
                damageToOpponents = a.dmgToDeck.map { NamedValue(it.key, it.value.toDouble() / (gamesWith[it.key] ?: 1)) }
                    .sortedByDescending { it.value },
                removalsPerGame = a.removals.toDouble() / games,
                removedRate = if (a.casts > 0) a.removed.toDouble() / a.casts else 0.0,
                removedBy = a.removedBy.entries.sortedByDescending { it.value }.take(8).map { NamedValue(it.key, it.value.toDouble()) },
            )
        }

        val cmdNames = deck.commanders.map { it.name }.toSet()
        val sources = cards.filter { it.value.damage > 0 }.entries.sortedByDescending { it.value.damage }.take(15).map { (name, a) ->
            SourceDamage(
                card = name,
                perGame = a.damage.toDouble() / games,
                combat = a.combat.toDouble() / games,
                nonCombat = a.nonCombat.toDouble() / games,
                commander = if (name in cmdNames) a.combat.toDouble() / games else 0.0,
                killShots = a.killShots,
                perOpponent = a.dmgToDeck.map { NamedValue(it.key, it.value.toDouble() / (gamesWith[it.key] ?: 1)) }
                    .sortedByDescending { it.value },
                byRound = a.dmgByRound.map { it.toDouble() / games },
            )
        }
        val damage = DamageStats(
            avgDealtToPlayers = per(dmgPlayers), avgCombat = per(dmgCombat), avgNonCombat = per(dmgNonCombat),
            avgCommanderDamage = per(dmgCmd), avgDealtToCreatures = per(dmgCreatures),
            avgTaken = per(taken), avgTakenCombat = per(takenCombat), avgTakenNonCombat = per(takenNonCombat),
            avgTakenCommander = per(takenCmd), avgLifeGained = per(lifeGained),
            perTurnDealt = dmgByTurn.mean(), perTurnTaken = takenByTurn.mean(),
            byTarget = dmgByTarget.map { NamedValue(it.key, it.value / (gamesWith[it.key] ?: 1)) }.sortedByDescending { it.value },
            takenByDeck = takenByDeck.map { NamedValue(it.key, it.value / (gamesWith[it.key] ?: 1)) }.sortedByDescending { it.value },
            takenByCard = topLong(takenByCard, 12) { it },
            sources = sources,
            killsPerGame = per(kills),
            lethalAgainstUs = lethal.entries.sortedByDescending { it.value }.take(10)
                .map { LethalSource(it.key.substringBeforeLast("|"), it.key.substringAfterLast("|"), it.value) },
        )

        val removal = RemovalStats(
            spellsPerGame = per(removalSpells), permanentsRemovedPerGame = per(permsRemoved),
            removedByType = removedByType.map { NamedValue(it.key.label, it.value.toDouble() / games) }.sortedByDescending { it.value },
            receivedPerGame = per(received), counterspellsPerGame = per(counters), boardWipesPerGame = per(wipes),
            sacrificedPerGame = per(sacrificed),
            targetsByOpponent = removalAt.map { NamedValue(it.key, it.value / (gamesWith[it.key] ?: 1)) }.sortedByDescending { it.value },
            mostRemovedOwnCards = topLong(ownRemoved, 8) { it },
        )
        val resources = ResourceStats(
            avgCardsDrawn = per(drawn), drawsPerTurn = drawsByTurn.mean(), avgHandSizeByTurn = handByTurn.mean(),
            avgMaxHandSize = per(maxHandSum), avgDiscarded = per(discarded), avgLandsPlayed = per(landsPlayed),
            landsInPlayByTurn = landsInPlay.mean(), landDropHitRate = landHit.mean(), avgManaSpentByTurn = manaByTurn.mean(),
            mulliganRate = mulliganGames.toDouble() / games, avgMulligans = per(mulligans), avgMilled = per(milled),
        )
        val commander = CommanderStats(
            castsPerGame = per(cmdCasts),
            avgFirstCastTurn = if (cmdFirstN > 0) cmdFirstSum.toDouble() / cmdFirstN else null,
            avgTaxPaid = per(cmdTax), removedPerGame = per(cmdRemoved), damagePerGame = per(dmgCmd),
            winRateWhenCastByT5 = rate(cmdEarlyWins, cmdEarlyGames),
            winRateWhenNeverCast = rate(cmdNeverWins, cmdNeverGames),
        )
        val lifeStats = LifeStats(
            avgLifeByTurn = lifeAll.mean(), avgLifeByTurnWins = life.wins.mean(), avgLifeByTurnLosses = life.losses.mean(),
            opponentsAvgLifeByTurn = oppLife.mean(),
        )
        val split = WinLossSplit(
            wins = wins, losses = games - wins - draws,
            series = listOf(
                SplitSeries("Damage dealt per round", splitDamage.wins.mean(), splitDamage.losses.mean()),
                SplitSeries("Life total", life.wins.mean(), life.losses.mean()),
                SplitSeries("Lands in play", splitLands.wins.mean(), splitLands.losses.mean()),
                SplitSeries("Mana spent per round", splitMana.wins.mean(), splitMana.losses.mean()),
                SplitSeries("Permanents on board", splitBoard.wins.mean(), splitBoard.losses.mean()),
                SplitSeries("Hand size", splitHand.wins.mean(), splitHand.losses.mean()),
            ),
            scalars = scalarNames.indices.map { i ->
                SplitScalar(
                    scalarNames[i],
                    if (scalarN[0][i] > 0) scalarSum[0][i] / scalarN[0][i] else 0.0,
                    if (scalarN[1][i] > 0) scalarSum[1][i] / scalarN[1][i] else 0.0,
                    scalarHigherBetter[i],
                )
            },
        )

        val minFaced = maxOf(10, games / 20)
        val facedStats = faced.filter { it.value[0] >= minFaced }.map { (k, v) ->
            val wr = v[1].toDouble() / v[0]
            NemesisStat(k.substringBeforeLast("|"), k.substringAfterLast("|"), v[0], wr, wr - winRate)
        }
        val threats = ThreatStats(
            nemesisCards = facedStats.filter { it.delta < 0 }.sortedBy { it.delta }.take(10),
            favourableCards = facedStats.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(6),
        )

        val report = DeckReport(
            deckId = deck.id, name = deck.name, commander = deck.commanderLabel, colorIdentity = deck.colorIdentity,
            outcome = outcome, damage = damage, cards = cardStats, removal = removal, resources = resources,
            commanderStats = commander,
            combat = CombatStats(
                attacksPerGame = per(attacks),
                avgAttackers = if (attacks > 0) attackers.toDouble() / attacks else 0.0,
                blocksPerGame = per(blocks), creaturesLostPerGame = per(lost),
            ),
            board = BoardStats(
                avgPermanentsByTurn = permsByTurn.mean(), avgCreaturesByTurn = creaturesByTurn.mean(),
                tokensPerGame = per(tokens), countersPerGame = per(countersPlaced),
            ),
            life = lifeStats, winLoss = split, threats = threats, insights = emptyList(),
        )
        return report.copy(insights = insights(report, deckNames))
    }

    private fun insights(d: DeckReport, names: Map<String, String>): List<Insight> {
        val out = mutableListOf<Insight>()
        fun pts(x: Double) = "%+.1f".format(x * 100)
        val playable = d.cards.filter { it.type != CardType.LAND }
        playable.filter { it.verdict == CardVerdict.WINNER }.sortedByDescending { it.impact }.take(3).forEach {
            out += Insight(InsightKind.GOOD, "${it.name} drives wins",
                "Win rate ${pts(it.impact!!)} pts when cast (${it.gamesCast} games).")
        }
        playable.filter { it.verdict == CardVerdict.WEAK }.sortedBy { it.impact }.take(3).forEach {
            out += Insight(InsightKind.BAD, "${it.name} is a weak point",
                "Win rate ${pts(it.impact!!)} pts when cast (${it.gamesCast} games).")
        }
        playable.filter { it.gamesSeen >= 20 && it.deadRate >= 0.5 }.sortedByDescending { it.deadRate }.take(2).forEach {
            out += Insight(InsightKind.BAD, "${it.name} gets stuck in hand",
                "Drawn but never cast in ${"%.0f".format(it.deadRate * 100)}% of games where it was seen.")
        }
        d.threats.nemesisCards.firstOrNull()?.let {
            out += Insight(InsightKind.BAD, "Watch out for ${it.card}",
                "When ${names[it.deckId] ?: "an opponent"} resolves it, your win rate is ${pts(it.delta)} pts vs your average.")
        }
        d.damage.sources.firstOrNull()?.let { s ->
            val share = if (d.damage.avgDealtToPlayers > 0) s.perGame / d.damage.avgDealtToPlayers else 0.0
            out += Insight(InsightKind.INFO, "Top damage: ${s.card}",
                "%.1f damage per game, %.0f%% of your total, %d kill shots.".format(s.perGame, share * 100, s.killShots))
        }
        d.damage.lethalAgainstUs.firstOrNull()?.let {
            out += Insight(InsightKind.INFO, "Most common way you die",
                "${it.card} (${names[it.deckId] ?: "opponent"}) dealt the final blow ${it.count} times.")
        }
        val early = d.commanderStats.winRateWhenCastByT5
        val never = d.commanderStats.winRateWhenNeverCast
        if (early != null && abs(early - d.outcome.winRate) > 0.03) {
            out += Insight(if (early > d.outcome.winRate) InsightKind.INFO else InsightKind.BAD, "Commander timing",
                "Win rate ${"%.0f".format(early * 100)}% when cast by round 5" +
                    (never?.let { ", ${"%.0f".format(it * 100)}% when never cast." } ?: "."))
        }
        return out
    }
}
