package com.eddiew.commandersim.sim

import com.eddiew.commandersim.data.model.AbComparison
import com.eddiew.commandersim.data.model.AbOpponent
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.MatchupStat
import com.eddiew.commandersim.data.model.MetricDelta
import com.eddiew.commandersim.data.model.SimConfig
import com.eddiew.commandersim.data.model.SimMeta
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.data.model.diffDecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * On-device sample simulator built on [ToyGame]. Produces every field of the report.
 *
 * Pool mode: every selected deck plays the others in balanced 4-player pods.
 * A/B mode: deck A and deck B each play the same pods against the field, with the same shuffle seed per game,
 * so the only difference between paired games is the swapped cards.
 */
class SampleSimulator internal constructor(
    private val seed: Long,
    private val player: GamePlayer,
    private val source: String,
    /** Extra notes for the report, read after the run (e.g. cards the engine didn't know). */
    private val notes: () -> List<String> = { emptyList() },
) : SimulationService {

    constructor(seed: Long = System.currentTimeMillis()) : this(seed, ToyGamePlayer(), "sample")

    override suspend fun run(config: SimConfig, decks: List<Deck>, control: SimControl, onProgress: (Float) -> Unit): SimulationReport =
        withContext(Dispatchers.Default) {
            try {
                runInner(config, decks, control, onProgress)
            } finally {
                player.close()
            }
        }

    private suspend fun runInner(config: SimConfig, decks: List<Deck>, control: SimControl, onProgress: (Float) -> Unit): SimulationReport =
        run {
            val byId = decks.associateBy { it.id }
            val pool = config.deckIds.distinct().mapNotNull { byId[it] }
            control.log("Preparing ${pool.size} decks…")
            player.prepare((pool + listOfNotNull(config.ab?.let { byId[it.aId] }, config.ab?.let { byId[it.bId] })).distinctBy { it.id }, control)
            control.log("Playing games…")
            val run = Run(Random(seed), config, control, onProgress)
            val ab = config.ab
            val comparison = if (ab != null) {
                val a = byId[ab.aId] ?: error("Deck A no longer exists.")
                val b = byId[ab.bId] ?: error("Deck B no longer exists.")
                val field = pool.filter { it.id != a.id && it.id != b.id }
                require(field.isNotEmpty()) { "Add at least one other deck to the pool to test A against B." }
                run.abTest(a, b, field)
            } else {
                require(pool.size >= 2) { "Select at least two decks for the pool." }
                run.pool(pool)
                null
            }
            onProgress(1f)
            require(run.games > 0) { "No games finished." + (run.firstError?.let { " First error: $it" } ?: "") }
            val extra = buildList {
                addAll(notes())
                if (run.adjudicated > 0) add("${run.adjudicated} of ${run.games} games reached the round limit and were scored on life and board.")
                if (run.failed > 0) add("${run.failed} games failed and were skipped (${run.firstError}).")
                if (control.stopRequested) add("Stopped early after ${run.games} games; results are less certain.")
            }
            run.report(comparison, ab?.let { listOf(it.aId to "A", it.bId to "B") }?.toMap() ?: emptyMap()).copy(notes = extra)
        }

    private class PairAcc {
        var games = 0
        var wins = 0
        var damage = 0.0
        var removal = 0.0
    }

    private inner class Run(val rng: Random, val config: SimConfig, val control: SimControl, val onProgress: (Float) -> Unit) {
        val acc = LinkedHashMap<String, DeckAccumulator>()
        val pairs = HashMap<Pair<String, String>, PairAcc>()
        var games = 0
        var timeouts = 0
        var turnSum = 0L
        val nanoSum = AtomicLong(0)
        var adjudicated = 0
        val roundHist = IntArray(MAX_TURNS)

        var failed = 0
        var firstError: String? = null

        /** Adds one finished game to every statistic. */
        fun record(seats: List<Deck>, result: GameResult) {
            games++
            control.gameIndex = games + 1
            turnSum += result.rounds
            roundHist[(result.rounds - 1).coerceIn(0, MAX_TURNS - 1)]++
            if (result.winnerSeat == null) timeouts++
            if (result.adjudicated) adjudicated++
            val ids = seats.map { it.id }
            seats.forEachIndexed { seat, deck ->
                acc.getOrPut(deck.id) { DeckAccumulator(deck) }.add(result, seat, ids)
                for ((otherSeat, other) in seats.withIndex()) {
                    if (otherSeat == seat) continue
                    val pa = pairs.getOrPut(deck.id to other.id) { PairAcc() }
                    pa.games++
                    if (result.winnerSeat == seat) pa.wins++
                    pa.damage += result.tracks[seat].dmgBySeat[otherSeat]
                    pa.removal += result.tracks[seat].removalAtSeat[otherSeat]
                }
            }
        }

        inner class Job(val seats: List<Deck>, val seed: Long)

        /**
         * Plays [jobs] on up to [GamePlayer.parallelism] threads and hands each result to [onResult] on this
         * coroutine, as games finish. Stops taking new games when the user asks to stop.
         */
        suspend fun execute(jobs: List<Job>, onResult: (Int, GameResult) -> Unit) = coroutineScope {
            val next = AtomicInteger(0)
            val out = Channel<Pair<Int, Result<GameResult>>>(Channel.UNLIMITED)
            val threads = player.parallelism.coerceAtLeast(1)
            val workers = List(threads) {
                launch(Dispatchers.IO) {
                    while (!control.stopRequested) {
                        val i = next.getAndIncrement()
                        if (i >= jobs.size) break
                        val start = System.nanoTime()
                        val r = runCatching { runInterruptible { player.play(jobs[i].seats, jobs[i].seed) } }
                        if (r.exceptionOrNull() is CancellationException) throw r.exceptionOrNull()!!
                        nanoSum.addAndGet(System.nanoTime() - start)
                        out.send(i to r)
                    }
                }
            }
            launch { workers.joinAll(); out.close() }
            var done = 0
            for ((i, r) in out) {
                done++
                r.onSuccess { onResult(i, it) }.onFailure { e ->
                    failed++
                    if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                    control.log("Game ${i + 1} failed: ${e.message}")
                }
                onProgress(done / jobs.size.toFloat())
            }
        }

        /** Picks the [k] decks with the fewest games so far (random tie-break), so every deck plays about equally. */
        fun pickBalanced(from: List<Deck>, counts: HashMap<String, Int>, k: Int): List<Deck> =
            from.shuffled(rng).sortedBy { counts[it.id] ?: 0 }.take(k).onEach { counts.merge(it.id, 1, Int::plus) }

        fun seatOrder(pod: List<Deck>): List<Deck> =
            if (config.seatRotation) pod.shuffled(rng) else pod

        suspend fun pool(pool: List<Deck>) {
            val podSize = min(config.podSize, pool.size)
            val total = ceil(pool.size * config.gamesPerDeck / podSize.toDouble()).toInt()
            control.gamesTotal = total
            val counts = HashMap<String, Int>()
            val jobs = List(total) { Job(seatOrder(pickBalanced(pool, counts, podSize)), rng.nextLong()) }
            execute(jobs) { i, r -> record(jobs[i].seats, r) }
        }

        suspend fun abTest(a: Deck, b: Deck, field: List<Deck>): AbComparison {
            val opps = min(config.podSize - 1, field.size)
            val counts = HashMap<String, Int>()
            control.gamesTotal = config.gamesPerDeck * 2
            // Each pair of jobs is the same game (opponents, seat, seed) with A and then B.
            val seatOf = IntArray(config.gamesPerDeck)
            val othersOf = ArrayList<List<Deck>>()
            val jobs = ArrayList<Job>()
            for (g in 0 until config.gamesPerDeck) {
                val others = pickBalanced(field, counts, opps)
                val seat = if (config.seatRotation) g % (opps + 1) else rng.nextInt(opps + 1)
                val gameSeed = rng.nextLong()
                seatOf[g] = seat
                othersOf += others
                jobs += Job(others.toMutableList().apply { add(seat, a) }, gameSeed)
                jobs += Job(others.toMutableList().apply { add(seat, b) }, gameSeed)
            }
            val results = arrayOfNulls<GameResult>(jobs.size)
            execute(jobs) { i, r -> results[i] = r; record(jobs[i].seats, r) }

            var n = 0
            var aWins = 0; var bWins = 0; var onlyA = 0; var onlyB = 0
            var diffSum = 0.0; var diffSq = 0.0
            val perOpp = HashMap<String, IntArray>() // games, aWins, bWins
            for (g in 0 until config.gamesPerDeck) {
                val ra = results[2 * g] ?: continue
                val rb = results[2 * g + 1] ?: continue
                n++
                val seat = seatOf[g]
                val wa = if (ra.winnerSeat == seat) 1 else 0
                val wb = if (rb.winnerSeat == seat) 1 else 0
                aWins += wa; bWins += wb
                if (wa == 1 && wb == 0) onlyA++
                if (wb == 1 && wa == 0) onlyB++
                val d = (wb - wa).toDouble()
                diffSum += d; diffSq += d * d
                for (o in othersOf[g]) perOpp.getOrPut(o.id) { IntArray(3) }.let { it[0]++; it[1] += wa; it[2] += wb }
            }
            n = n.coerceAtLeast(1)
            val mean = diffSum / n
            val variance = if (n > 1) (diffSq - n * mean * mean) / (n - 1) else 0.0
            val se = sqrt(variance.coerceAtLeast(0.0) / n)
            val diff = diffDecks(a, b)
            return AbComparison(
                aId = a.id, bId = b.id, games = n, aWins = aWins, bWins = bWins,
                aRate = aWins.toDouble() / n, bRate = bWins.toDouble() / n,
                diff = mean, diffLow = mean - 1.96 * se, diffHigh = mean + 1.96 * se,
                onlyA = onlyA, onlyB = onlyB,
                cardsOut = diff.removed.map { if (it.quantity > 1) "${it.quantity} ${it.name}" else it.name },
                cardsIn = diff.added.map { if (it.quantity > 1) "${it.quantity} ${it.name}" else it.name },
                metrics = emptyList(), // filled in once both deck reports exist
                perOpponent = perOpp.map { (id, v) -> AbOpponent(id, v[0], v[1].toDouble() / v[0], v[2].toDouble() / v[0]) }
                    .sortedByDescending { it.games },
            )
        }

        fun report(ab: AbComparison?, sides: Map<String, String>): SimulationReport {
            val names = acc.mapValues { it.value.deck.name }
            val decks = acc.values.map { it.build(names).copy(abSide = sides[it.deck.id]) }
                .sortedByDescending { it.outcome.winRate }
            val fullAb = ab?.let { c ->
                val a = decks.first { it.deckId == c.aId }
                val b = decks.first { it.deckId == c.bId }
                c.copy(metrics = abMetrics(a, b))
            }
            return SimulationReport(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                source = source,
                config = config,
                decks = decks,
                matchups = pairs.map { (k, v) ->
                    MatchupStat(k.first, k.second, v.games, v.wins.toDouble() / v.games, v.damage / v.games, v.removal / v.games)
                },
                meta = SimMeta(
                    games = games, timeouts = timeouts, adjudicated = 0,
                    avgTurns = turnSum.toDouble() / games.coerceAtLeast(1),
                    avgGameMs = nanoSum.get() / 1e6 / games.coerceAtLeast(1),
                    roundHistogram = roundHist.toList(),
                ),
                ab = fullAb,
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

        internal fun abMetrics(a: DeckReport, b: DeckReport): List<MetricDelta> {
            fun winTurn(d: DeckReport): Double {
                val h = d.outcome.winTurnHistogram
                val n = h.sum()
                return if (n == 0) 0.0 else h.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / n
            }
            fun at6(l: List<Double>) = l.getOrElse(5) { 0.0 }
            val out = mutableListOf<MetricDelta>()
            fun m(group: String, name: String, f: (DeckReport) -> Double, higher: Boolean = true, pct: Boolean = false) {
                out += MetricDelta(group, name, f(a), f(b), higher, pct)
            }
            m("Outcome", "Win rate", { it.outcome.winRate }, pct = true)
            m("Outcome", "Avg finishing place", { it.outcome.avgPlace }, higher = false)
            m("Outcome", "Avg winning round", { winTurn(it) }, higher = false)
            m("Outcome", "Avg round eliminated", { it.outcome.avgEliminationTurn ?: 0.0 })
            m("Damage", "Damage to players / game", { it.damage.avgDealtToPlayers })
            m("Damage", "Combat damage / game", { it.damage.avgCombat })
            m("Damage", "Non-combat damage / game", { it.damage.avgNonCombat })
            m("Damage", "Commander damage / game", { it.damage.avgCommanderDamage })
            m("Damage", "Damage taken / game", { it.damage.avgTaken }, higher = false)
            m("Damage", "Kills / game", { it.damage.killsPerGame })
            m("Cards", "Cards drawn / game", { it.resources.avgCardsDrawn })
            m("Cards", "Avg max hand size", { it.resources.avgMaxHandSize })
            m("Cards", "Mulligan rate", { it.resources.mulliganRate }, higher = false, pct = true)
            m("Cards", "Mana spent by round 6", { it.resources.avgManaSpentByTurn.take(6).sum() })
            m("Interaction", "Removal cast / game", { it.removal.spellsPerGame })
            m("Interaction", "Opposing permanents removed / game", { it.removal.permanentsRemovedPerGame })
            m("Interaction", "Own permanents lost / game", { it.removal.receivedPerGame }, higher = false)
            m("Interaction", "Counterspells / game", { it.removal.counterspellsPerGame })
            m("Board", "Permanents at round 6", { at6(it.board.avgPermanentsByTurn) })
            m("Board", "Creatures at round 6", { at6(it.board.avgCreaturesByTurn) })
            m("Board", "Commander first cast (round)", { it.commanderStats.avgFirstCastTurn ?: 0.0 }, higher = false)
            m("Board", "Life at round 6", { at6(it.life.avgLifeByTurn) })
            return out
        }
    }
}
