package com.eddiew.commandersim.sim.forge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.sim.BridgeDeck
import com.eddiew.commandersim.sim.GamePlayer
import com.eddiew.commandersim.sim.GameResult
import com.eddiew.commandersim.sim.SampleSimulator
import com.eddiew.commandersim.sim.SimControl
import com.eddiew.commandersim.sim.SimulationService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Settings for Forge games. */
data class ForgeOptions(
    val workers: Int = 2,
    /** Games still going at this round are stopped and scored on life and board. */
    val maxRounds: Int = 18,
    val maxMillisPerGame: Long = 6 * 60_000L,
)

object AndroidForge {
    private val workerClasses = listOf(ForgeWorker1::class.java, ForgeWorker2::class.java, ForgeWorker3::class.java, ForgeWorker4::class.java)
    const val MAX_WORKERS = 4

    /** A simulator that plays every game with Forge's rules engine and AI, in [ForgeOptions.workers] processes. */
    fun simulator(context: Context, packs: ForgePacks, pool: WorkerPool, options: ForgeOptions, seed: Long = System.currentTimeMillis()): SimulationService {
        val player = ForgeWorkersPlayer(context.applicationContext, packs, pool, options)
        return SampleSimulator(seed, player, "forge") { player.notes() }
    }

    /** One bound worker process. */
    class Worker(val index: Int) {
        @Volatile var api: IForgeWorker? = null
        var connection: ServiceConnection? = null
        @Volatile var lastStatus: String = ""
    }

    /**
     * Keeps worker processes bound while the app is open, so each keeps its loaded card database between runs.
     */
    class WorkerPool(private val context: Context) {
        private val workers = arrayOfNulls<Worker>(MAX_WORKERS)

        /** Binds worker [i] (if not already) and waits until it's connected. */
        @Synchronized
        fun get(i: Int): Worker {
            workers[i]?.takeIf { it.api?.asBinder()?.isBinderAlive == true }?.let { return it }
            workers[i]?.connection?.let { runCatching { context.unbindService(it) } }
            val w = Worker(i)
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    w.api = IForgeWorker.Stub.asInterface(service)
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) { w.api = null }
            }
            w.connection = conn
            check(context.bindService(Intent(context, workerClasses[i]), conn, Context.BIND_AUTO_CREATE)) { "Couldn't start Forge worker ${i + 1}" }
            check(latch.await(30, TimeUnit.SECONDS)) { "Forge worker ${i + 1} didn't start" }
            workers[i] = w
            return w
        }

        /** Unbinds workers beyond [keep] so their memory is freed. */
        @Synchronized
        fun trim(keep: Int) {
            for (i in keep until MAX_WORKERS) {
                workers[i]?.connection?.let { runCatching { context.unbindService(it) } }
                workers[i] = null
            }
        }

        fun close() = trim(0)

        /** The worker if it's bound, without binding it. */
        fun peek(i: Int): Worker? = workers[i]
    }

    private class ForgeWorkersPlayer(
        private val context: Context,
        private val packs: ForgePacks,
        private val pool: WorkerPool,
        private val options: ForgeOptions,
    ) : GamePlayer {
        private val count = options.workers.coerceIn(1, MAX_WORKERS)
        override val parallelism: Int get() = count
        private val idle = LinkedBlockingQueue<Worker>()
        private var missing: List<String> = emptyList()
        private var packLabel = ""
        private var control: SimControl? = null
        private var prepareArgs: Array<Any>? = null
        @Volatile private var running = true

        override fun prepare(decks: List<Deck>, control: SimControl) {
            this.control = control
            val pack = packs.refresh() ?: error("No Forge engine is installed.")
            packLabel = pack.manifest.label + if (pack.bundled) " (built in)" else ""
            control.log("Engine: $packLabel")
            val res = packs.unpackResources(pack) { control.log(it) }
            val bridgeDecks = decks.map { d ->
                BridgeDeck(d.id, d.name, d.commanders.map { it.name }, d.cards.map { it.name to it.quantity })
            }
            val bytes = ArrayList(bridgeDecks).toBytes()
            prepareArgs = arrayOf(pack.apk.path, res.path, bytes)
            pool.trim(count)
            control.log("Starting $count Forge worker${if (count > 1) "s" else ""}…")
            // Prepare all workers at once (each loads its own card database).
            val errors = mutableListOf<String>()
            val threads = (0 until count).map { i ->
                thread(name = "prepare-$i") {
                    try {
                        val w = pool.get(i)
                        prepareWorker(w)
                        synchronized(idle) { idle.put(w) }
                    } catch (e: Throwable) {
                        synchronized(errors) { errors += "Worker ${i + 1}: ${e.message}" }
                    }
                }
            }
            threads.forEach { it.join() }
            errors.forEach { control.log(it) }
            if (idle.isEmpty()) error(errors.firstOrNull() ?: "No Forge worker could start.")
            if (missing.isNotEmpty()) control.log("Forge doesn't know: ${missing.take(8).joinToString(", ")}")
            control.log("Playing games on ${idle.size} worker${if (idle.size > 1) "s" else ""}…")
            // Show what every worker is doing, once a second.
            thread(isDaemon = true, name = "forge-status") {
                while (running) {
                    Thread.sleep(1000)
                    val parts = (0 until count).mapNotNull { i ->
                        runCatching { pool.peek(i)?.let { w -> w.api?.status()?.also { w.lastStatus = it } } }.getOrNull()
                            ?.let { if (count > 1) "W${i + 1} $it" else it }
                    }
                    val ctl = this.control ?: continue
                    ctl.status = "Game ${ctl.gameIndex}/${ctl.gamesTotal} · " + parts.joinToString(" · ")
                }
            }
        }

        private fun prepareWorker(w: Worker) {
            val (pack, res, bytes) = prepareArgs ?: error("not prepared")
            val work = File(context.cacheDir, "forge-work-${w.index}").path
            val reply: WorkerReply = (w.api ?: error("disconnected")).prepare(pack as String, res as String, work, bytes as ByteArray).fromBytes()
            reply.error?.let { error(it) }
            if (w.index == 0 || missing.isEmpty()) missing = reply.missing
        }

        override fun play(seats: List<Deck>, seed: Long): GameResult {
            val ids = seats.map { it.id }.toTypedArray()
            var attempt = 0
            while (true) {
                val w = idle.take()
                try {
                    val api = w.api ?: throw DeadObjectException()
                    val reply: WorkerReply = api.play(ids, seed, options.maxRounds, options.maxMillisPerGame).fromBytes()
                    idle.put(w)
                    reply.error?.let { error("Forge error: $it") }
                    return reply.result ?: error("Forge worker returned no result")
                } catch (e: Exception) {
                    if (e !is DeadObjectException && e !is android.os.RemoteException) {
                        idle.put(w)
                        throw e
                    }
                    // The worker process died (usually out of memory): restart it and try the game once more.
                    control?.log("Worker ${w.index + 1} stopped (probably out of memory); restarting it")
                    val fresh = runCatching { pool.get(w.index).also { prepareWorker(it) } }.getOrNull()
                    if (fresh != null) idle.put(fresh)
                    else control?.log("Worker ${w.index + 1} couldn't restart; continuing with fewer workers")
                    if (++attempt >= 2 || idle.isEmpty()) throw IllegalStateException("Forge worker crashed twice on the same game", e)
                }
            }
        }

        override fun close() {
            running = false
        }

        fun notes(): List<String> = listOfNotNull(
            "Played with the Forge rules engine and Forge AI ($packLabel).",
            if (missing.isEmpty()) null
            else "Forge doesn't know ${missing.size} card${if (missing.size > 1) "s" else ""}, so they were left out: " +
                missing.take(12).joinToString(", ") + if (missing.size > 12) "…" else "",
        )
    }
}
