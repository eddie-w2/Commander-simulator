package com.eddiew.commandersim.sim.forge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.eddiew.commandersim.sim.BridgeDeck
import com.eddiew.commandersim.sim.ForgeBridge
import com.eddiew.commandersim.sim.GameResult
import dalvik.system.DexClassLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Reply from a worker call. [error] is set instead of throwing, so the details reach the app. */
class WorkerReply(
    val missing: List<String> = emptyList(),
    val result: GameResult? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
) : Serializable

internal fun Serializable.toBytes(): ByteArray =
    ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(this) } }.toByteArray()

@Suppress("UNCHECKED_CAST")
internal fun <T> ByteArray.fromBytes(): T = ObjectInputStream(ByteArrayInputStream(this)).use { it.readObject() as T }

/**
 * Runs Forge games in its own process (see the :forgeN processes in the manifest), so several games can run at once
 * and a crash or out-of-memory in Forge never takes the app down. Loads the Forge pack with DexClassLoader.
 */
open class ForgeWorkerService : Service() {
    @Volatile private var status: String = "idle"
    private var bridge: ForgeBridge? = null
    private var bridgePack: String? = null

    private fun bridgeFor(packPath: String): ForgeBridge {
        bridge?.takeIf { bridgePack == packPath }?.let { return it }
        val loader = DexClassLoader(packPath, File(codeCacheDir, "forgepack").apply { mkdirs() }.path, null, javaClass.classLoader)
        val b = loader.loadClass("com.eddiew.commandersim.forgepack.Bridge").getDeclaredConstructor().newInstance() as ForgeBridge
        bridge = b
        bridgePack = packPath
        return b
    }

    private fun describe(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.take(4).joinToString(" < ")}" +
            (e.cause?.let { " | cause ${it.javaClass.simpleName}: ${it.message}" } ?: "")

    private val binder = object : IForgeWorker.Stub() {
        override fun prepare(packPath: String, resPath: String, workPath: String, decks: ByteArray): ByteArray {
            val log = mutableListOf<String>()
            return try {
                status = "loading"
                val list: List<BridgeDeck> = decks.fromBytes()
                val missing = bridgeFor(packPath).prepare(File(resPath), File(workPath), list) { log += it; status = it }
                status = "ready"
                WorkerReply(missing = missing, log = log).toBytes()
            } catch (e: Throwable) {
                status = "error"
                WorkerReply(error = describe(e), log = log).toBytes()
            }
        }

        override fun play(seatDeckIds: Array<String>, seed: Long, maxRounds: Int, maxMillis: Long): ByteArray {
            val b = bridge ?: return WorkerReply(error = "Worker not prepared").toBytes()
            return try {
                status = "starting"
                val r = b.play(seatDeckIds.toList(), seed, maxRounds, maxMillis) { status = it }
                status = "idle"
                WorkerReply(result = r).toBytes()
            } catch (e: Throwable) {
                status = "error"
                WorkerReply(error = describe(e)).toBytes()
            }
        }

        override fun status(): String = status
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

// Each worker process needs its own service class.
class ForgeWorker1 : ForgeWorkerService()
class ForgeWorker2 : ForgeWorkerService()
class ForgeWorker3 : ForgeWorkerService()
class ForgeWorker4 : ForgeWorkerService()
