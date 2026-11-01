package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.BoardStats
import com.eddiew.commandersim.data.model.CardStat
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.CombatStats
import com.eddiew.commandersim.data.model.CommanderStats
import com.eddiew.commandersim.data.model.ConditionCount
import com.eddiew.commandersim.data.model.DamageStats
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.data.model.EndCondition
import com.eddiew.commandersim.data.model.MatchupStat
import com.eddiew.commandersim.data.model.NamedValue
import com.eddiew.commandersim.data.model.OutcomeStats
import com.eddiew.commandersim.data.model.RemovalStats
import com.eddiew.commandersim.data.model.ResourceStats
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.data.model.SimMeta
import com.eddiew.commandersim.data.model.SimulationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** On-device sample simulator built on [ToyGame]. Produces every field of the report. */
class SampleSimulator(private val seed: Long = System.currentTimeMillis()) : SimulationService {

    override suspend fun run(config: SimConfig, decks: List<Deck>, onProgress: (Float) -> Unit): SimulationReport =
        withContext(Dispatchers.Default) {
            val rng = Random(seed)
            val byId = decks.associateBy { it.id }
            val candidates = config.candidateIds.mapNotNull { byId[it] }
            val opponents = config.opponentIds.mapNotNull { byId[it] }.filter { o -> candidates.none { it.id == o.id } }
            require(candidates.isNotEmpty()) { "Pick at least one candidate deck." }
            require(opponents.isNotEmpty()) { "Pick at least one opponent deck." }

            val profiles = (candidates + opponents).associate { it.id to DeckProfile.from(it) }
            val acc = HashMap<String, DeckAccumulator>()
            val pairs = HashMap<Pair<String, String>, PairAccumulator>()
            var games = 0
            var timeouts = 0
            var turnSum = 0L
            var nanoSum = 0L
            val total = candidates.size * config.gamesPerCandidate

            for (cand in candidates) {
                for (g in 0 until config.gamesPerCandidate) {
                    ensureActive()
                    val picks = opponents.shuffled(rng).take(min(config.podSize - 1, opponents.size))
                    val pod = listOf(cand) + picks
                    val seats = if (config.seatRotation) {
                        val shift = g % pod.size
                        List(pod.size) { pod[(it - shift + pod.size) % pod.size] }
                    } else pod.shuffled(rng)

                    val start = System.nanoTime()
                    val result = ToyGame(seats.map { profiles.getValue(it.id) }, rng).play()
                    nanoSum += System.nanoTime() - start
                    games++
                    turnSum += result.rounds
                    if (result.winnerSeat == null) timeouts++

                    seats.forEachIndexed { seat, deck ->
                        val role = if (candidates.any { it.id == deck.id }) DeckRole.CANDIDATE else DeckRole.OPPONENT
                        acc.getOrPut(deck.id) { DeckAccumulator(deck, role) }
                            .add(result, seat, seats.map { it.id })
                        for ((otherSeat, other) in seats.withIndex()) {
                            if (otherSeat == seat) continue
                            val pa = pairs.getOrPut(deck.id to other.id) { PairAccumulator() }
                            pa.games++
                            if (result.winnerSeat == seat) pa.wins++
                            pa.damage += result.tracks[seat].dmgBySeat[otherSeat]
                            pa.removal += result.tracks[seat].removalAtSeat[otherSeat]
                        }
                    }
                    if (games % 20 == 0) onProgress(games / total.toFloat())
                }
            }
            onProgress(1f)

            val names = (candidates + opponents).associate { it.id to it.name }
            SimulationReport(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                source = "sample",
                config = config,
                decks = acc.values.map { it.build(names) }
                    .sortedWith(compareBy<DeckReport> { it.role }.thenByDescending { it.outcome.winRate }),
                matchups = pairs.map { (k, v) ->
                    MatchupStat(k.first, k.second, v.games, v.wins.toDouble() / v.games, v.damage / v.games, v.removal / v.games)
                },
                meta = SimMeta(
                    games = games,
                    timeouts = timeouts,
                    adjudicated = 0,
                    avgTurns = turnSum.toDouble() / games,
                    avgGameMs = nanoSum / 1e6 / games,
                ),
            )
        }

    private class PairAccumulator {
        var games = 0
        var wins = 0
        var damage = 0.0
        var removal = 0.0
    }

    private class CardAcc(val type: CardType, val mv: Double?) {
        var seenGames = 0
        var castGames = 0
        var casts = 0
        var castTurnSum = 0
        var castWins = 0
        var notCastGames = 0
        var notCastWins = 0
        var deadGames = 0
        var countered = 0
        var damage = 0
        var removals = 0
        var removed = 0
    }

    private class DeckAccumulator(val deck: Deck, val role: DeckRole) {
        var games = 0
        var wins = 0
        var draws = 0
        val placements = IntArray(4)
        var elimTurnSum = 0
        var elimCount = 0
        val winTurns = IntArray(MAX_TURNS)
        val winCond = HashMap<EndCondition, Int>()
        val lossCause = HashMap<EndCondition, Int>()
        val seatGames = IntArray(4)
        val seatWins = IntArray(4)

        var dmgPlayers = 0L; var dmgCombat = 0L; var dmgNonCombat = 0L; var dmgCmd = 0L
        var dmgCreatures = 0L; var taken = 0L; var lifeGained = 0L
        val dmgByTurn = DoubleArray(MAX_TURNS)
        val dmgByTarget = HashMap<String, Double>()
        val targetGames = HashMap<String, Int>()
        val dmgBySource = HashMap<String, Long>()

        val cards = LinkedHashMap<String, CardAcc>()

        var removalSpells = 0L; var permsRemoved = 0L; var received = 0L; var counters = 0L
        var wipes = 0L; var sacrificed = 0L
        val removedByType = HashMap<CardType, Long>()
        val removalAt = HashMap<String, Double>()
        val ownRemoved = HashMap<String, Long>()

        var drawn = 0L; var maxHandSum = 0L; var discarded = 0L; var landsPlayed = 0L
        var mulliganGames = 0; var mulligans = 0L; var milled = 0L
        val drawsByTurn = DoubleArray(MAX_TURNS)
        val handByTurn = DoubleArray(MAX_TURNS); val handN = IntArray(MAX_TURNS)
        val landHit = DoubleArray(MAX_TURNS); val turnN = IntArray(MAX_TURNS)
        val manaByTurn = DoubleArray(MAX_TURNS)
        val permsByTurn = DoubleArray(MAX_TURNS); val creaturesByTurn = DoubleArray(MAX_TURNS); val boardN = IntArray(MAX_TURNS)

        var cmdCasts = 0L; var cmdFirstSum = 0L; var cmdFirstN = 0; var cmdTax = 0L; var cmdRemoved = 0L
        var cmdEarlyGames = 0; var cmdEarlyWins = 0

        var attacks = 0L; var attackers = 0L; var blocks = 0L; var lost = 0L
        var tokens = 0L; var countersPlaced = 0L

        init {
            (deck.commanders + deck.cards).forEach { c -> cards[c.name] = CardAcc(c.type, c.manaValue) }
        }

        fun add(r: GameResult, seat: Int, seatDeckIds: List<String>) {
            val t = r.tracks[seat]
            val won = r.winnerSeat == seat
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
            if (seat < 4) { seatGames[seat]++; if (won) seatWins[seat]++ }

            dmgPlayers += t.dmgPlayers; dmgCombat += t.dmgCombat; dmgNonCombat += t.dmgNonCombat
            dmgCmd += t.dmgCommander; dmgCreatures += t.dmgCreatures; taken += t.taken; lifeGained += t.lifeGained
            for (i in 0 until MAX_TURNS) dmgByTurn[i] += t.dmgByTurn[i].toDouble()
            seatDeckIds.forEachIndexed { s, id ->
                if (s != seat) {
                    dmgByTarget.merge(id, t.dmgBySeat[s].toDouble(), Double::plus)
                    removalAt.merge(id, t.removalAtSeat[s].toDouble(), Double::plus)
                    targetGames.merge(id, 1, Int::plus)
                }
            }
            t.dmgBySource.forEach { (k, v) -> dmgBySource.merge(k, v.toLong(), Long::plus) }

            for ((name, acc) in cards) {
                val ct = t.cards[name]
                val cast = ct != null && ct.casts > 0
                if (ct?.seen == true || cast) acc.seenGames++
                if (cast) {
                    acc.castGames++; acc.casts += ct!!.casts; acc.castTurnSum += ct.firstCastTurn
                    if (won) acc.castWins++
                } else {
                    acc.notCastGames++; if (won) acc.notCastWins++
                    if (ct?.seen == true) acc.deadGames++
                }
                if (ct != null) {
                    acc.countered += ct.countered; acc.damage += ct.damage
                    acc.removals += ct.removals; acc.removed += ct.removed
                }
            }

            removalSpells += t.removalSpells; permsRemoved += t.permsRemoved; received += t.received
            counters += t.counters; wipes += t.wipes; sacrificed += t.sacrificed
            t.removedByType.forEach { (k, v) -> removedByType.merge(k, v.toLong(), Long::plus) }
            t.ownRemoved.forEach { (k, v) -> ownRemoved.merge(k, v.toLong(), Long::plus) }

            drawn += t.drawn; maxHandSum += t.maxHand; discarded += t.discarded; landsPlayed += t.landsPlayed
            if (t.mulligans > 0) mulliganGames++
            mulligans += t.mulligans; milled += t.milled
            for (i in 0 until MAX_TURNS) {
                if (i < t.turnsTaken) {
                    turnN[i]++
                    drawsByTurn[i] += t.drawsByTurn[i].toDouble()
                    if (t.landByTurn[i]) landHit[i] += 1.0
                    manaByTurn[i] += t.manaByTurn[i].toDouble()
                }
                if (t.handByTurn[i] >= 0) { handByTurn[i] += t.handByTurn[i].toDouble(); handN[i]++ }
                if (t.permsByTurn[i] >= 0) {
                    permsByTurn[i] += t.permsByTurn[i].toDouble(); creaturesByTurn[i] += t.creaturesByTurn[i].toDouble(); boardN[i]++
                }
            }

            cmdCasts += t.cmdCasts
            if (t.cmdFirstCast > 0) { cmdFirstSum += t.cmdFirstCast; cmdFirstN++ }
            cmdTax += 2L * (0 until t.cmdCasts).sum()
            cmdRemoved += t.cmdRemoved
            if (t.cmdFirstCast in 1..5) { cmdEarlyGames++; if (won) cmdEarlyWins++ }

            attacks += t.attacks; attackers += t.attackersTotal; blocks += t.blocks; lost += t.creaturesLost
            tokens += t.tokens; countersPlaced += t.counterPlaced
        }

        private fun per(x: Long) = x.toDouble() / games
        private fun series(sum: DoubleArray, n: IntArray) = List(MAX_TURNS) { if (n[it] > 0) sum[it] / n[it] else 0.0 }
        private fun top(map: Map<String, Long>, k: Int) =
            map.entries.sortedByDescending { it.value }.take(k).map { NamedValue(it.key, it.value.toDouble() / games) }

        fun build(names: Map<String, String>): DeckReport {
            val (lo, hi) = wilson(wins, games)
            val outcome = OutcomeStats(
                games = games, wins = wins, draws = draws,
                winRate = wins.toDouble() / games, ci95Low = lo, ci95High = hi,
                placements = placements.toList(),
                avgEliminationTurn = if (elimCount > 0) elimTurnSum.toDouble() / elimCount else null,
                winTurnHistogram = winTurns.toList(),
                winConditions = winCond.map { ConditionCount(it.key, it.value) }.sortedByDescending { it.count },
                lossCauses = lossCause.map { ConditionCount(it.key, it.value) }.sortedByDescending { it.count },
                seatWinRate = List(4) { if (seatGames[it] > 0) seatWins[it].toDouble() / seatGames[it] else 0.0 },
            )
            val damage = DamageStats(
                avgDealtToPlayers = per(dmgPlayers), avgCombat = per(dmgCombat), avgNonCombat = per(dmgNonCombat),
                avgCommanderDamage = per(dmgCmd), avgDealtToCreatures = per(dmgCreatures), avgTaken = per(taken),
                avgLifeGained = per(lifeGained),
                perTurnDealt = series(dmgByTurn, turnN),
                byTarget = dmgByTarget.map { NamedValue(it.key, it.value / (targetGames[it.key] ?: 1)) }.sortedByDescending { it.value },
                topSources = top(dmgBySource, 12),
            )
            val cardStats = cards.map { (name, a) ->
                CardStat(
                    name = name, type = a.type, manaValue = a.mv,
                    seenRate = a.seenGames.toDouble() / games,
                    castRate = a.castGames.toDouble() / games,
                    avgCastTurn = if (a.castGames > 0) a.castTurnSum.toDouble() / a.castGames else null,
                    castsPerGame = a.casts.toDouble() / games,
                    deadRate = if (a.seenGames > 0) a.deadGames.toDouble() / a.seenGames else 0.0,
                    counteredRate = if (a.casts > 0) a.countered.toDouble() / a.casts else 0.0,
                    winRateWhenCast = if (a.castGames >= 5) a.castWins.toDouble() / a.castGames else null,
                    winRateWhenNotCast = if (a.notCastGames >= 5) a.notCastWins.toDouble() / a.notCastGames else null,
                    damagePerGame = a.damage.toDouble() / games,
                    removalsPerGame = a.removals.toDouble() / games,
                    removedRate = if (a.casts > 0) a.removed.toDouble() / a.casts else 0.0,
                )
            }
            val removal = RemovalStats(
                spellsPerGame = per(removalSpells), permanentsRemovedPerGame = per(permsRemoved),
                removedByType = removedByType.map { NamedValue(it.key.label, it.value.toDouble() / games) }.sortedByDescending { it.value },
                receivedPerGame = per(received), counterspellsPerGame = per(counters), boardWipesPerGame = per(wipes),
                sacrificedPerGame = per(sacrificed),
                targetsByOpponent = removalAt.map { NamedValue(it.key, it.value / (targetGames[it.key] ?: 1)) }.sortedByDescending { it.value },
                mostRemovedOwnCards = top(ownRemoved, 8),
            )
            val resources = ResourceStats(
                avgCardsDrawn = per(drawn),
                drawsPerTurn = series(drawsByTurn, turnN),
                avgHandSizeByTurn = series(handByTurn, handN),
                avgMaxHandSize = per(maxHandSum), avgDiscarded = per(discarded), avgLandsPlayed = per(landsPlayed),
                landDropHitRate = series(landHit, turnN),
                avgManaSpentByTurn = series(manaByTurn, turnN),
                mulliganRate = mulliganGames.toDouble() / games, avgMulligans = per(mulligans), avgMilled = per(milled),
            )
            val commander = CommanderStats(
                castsPerGame = per(cmdCasts),
                avgFirstCastTurn = if (cmdFirstN > 0) cmdFirstSum.toDouble() / cmdFirstN else null,
                avgTaxPaid = per(cmdTax), removedPerGame = per(cmdRemoved), damagePerGame = per(dmgCmd),
                winRateWhenCastByT5 = if (cmdEarlyGames >= 5) cmdEarlyWins.toDouble() / cmdEarlyGames else null,
            )
            return DeckReport(
                deckId = deck.id, name = deck.name, commander = deck.commanderLabel, colorIdentity = deck.colorIdentity,
                role = role, outcome = outcome, damage = damage, cards = cardStats, removal = removal,
                resources = resources, commanderStats = commander,
                combat = CombatStats(
                    attacksPerGame = per(attacks),
                    avgAttackers = if (attacks > 0) attackers.toDouble() / attacks else 0.0,
                    blocksPerGame = per(blocks), creaturesLostPerGame = per(lost),
                ),
                board = BoardStats(
                    avgPermanentsByTurn = series(permsByTurn, boardN), avgCreaturesByTurn = series(creaturesByTurn, boardN),
                    tokensPerGame = per(tokens), countersPerGame = per(countersPlaced),
                ),
            )
        }
    }

    companion object {
        /** Wilson score interval, 95%. */
        fun wilson(successes: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
            if (n == 0) return 0.0 to 0.0
            val p = successes.toDouble() / n
            val denom = 1 + z * z / n
            val centre = (p + z * z / (2 * n)) / denom
            val margin = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom
            return (centre - margin).coerceAtLeast(0.0) to (centre + margin).coerceAtMost(1.0)
        }
    }
}
