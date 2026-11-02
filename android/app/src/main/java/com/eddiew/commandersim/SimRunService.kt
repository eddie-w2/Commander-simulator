package com.eddiew.commandersim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app (and its Forge worker processes) running while a simulation runs in the
 * background. It does no work itself: it shows progress and stops when the run ends.
 */
class SimRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Simulations", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of running simulations"
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, ID, notification(SimState.Running(0f, "Starting…")),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (wakeLock == null) {
            // Keep the CPU awake with the screen off; released when the run ends (or after 8 hours at most).
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CommanderSim:simulation")
                .apply { acquire(8 * 60 * 60 * 1000L) }
        }
        val runner = AppServices.get(application).runner
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            var lastUpdate = 0L
            runner.state.collectLatest { s ->
                when (s) {
                    is SimState.Running -> {
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 2000) {
                            lastUpdate = now
                            getSystemService(NotificationManager::class.java).notify(ID, notification(s))
                        }
                    }
                    is SimState.Done -> finish("Simulation finished", "Tap to see the results.")
                    is SimState.Failed -> finish("Simulation stopped", "Finished games were saved. Open the app to resume.")
                    SimState.Idle -> finish(null, null)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(title: String?, text: String?) {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (title != null) {
            getSystemService(NotificationManager::class.java).notify(DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_sim)
                    .setContentTitle(title).setContentText(text)
                    .setContentIntent(openApp()).setAutoCancel(true)
                    .build())
        }
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(s: SimState.Running): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sim)
            .setContentTitle("Simulating · ${(s.progress * 100).toInt()}%")
            .setContentText(s.status.ifBlank { "Running games…" })
            .setProgress(100, (s.progress * 100).toInt(), s.progress <= 0f)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "simulation"
        private const val ID = 41
        private const val DONE_ID = 42

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SimRunService::class.java)) }
        }
    }
}
