package com.noway.edge.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.noway.edge.MainActivity
import com.noway.edge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScanForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOWAY:authorized-scan").apply { acquire(6 * 60 * 60 * 1000L) }
        observer = scope.launch { ScanManager.state.collectLatest { snapshot -> (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(snapshot)) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ScanManager.pause()
            ACTION_STOP -> ScanManager.stop()
            ACTION_START, ACTION_RESUME, null -> {
                startForeground(NOTIFICATION_ID, notification(ScanManager.state.value))
                ScanManager.runCheckpoint()
            }
        }
        return START_STICKY
    }

    private fun actionIntent(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request, Intent(this, ScanForegroundService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(snapshot: ScanSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NOWAY • ${snapshot.state.name.lowercase().replaceFirstChar(Char::uppercase)}")
            .setContentText("${snapshot.checked}/${snapshot.total} • ${snapshot.matched} matches • ${snapshot.cdnDetected} CDN")
            .setContentIntent(open).setOngoing(snapshot.state in setOf(ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING)).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (snapshot.total > 0 && snapshot.total <= Int.MAX_VALUE) builder.setProgress(snapshot.total.toInt(), snapshot.checked.coerceAtMost(snapshot.total).toInt(), false)
        if (snapshot.state in setOf(ScanState.RUNNING, ScanState.WAITING)) builder.addAction(0, "Pause", actionIntent(ACTION_PAUSE, 1)).addAction(0, "End", actionIntent(ACTION_STOP, 2))
        if (snapshot.state == ScanState.PAUSED) builder.addAction(0, "Resume", actionIntent(ACTION_RESUME, 3))
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Authorized scan progress", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress and safe controls for an active NOWAY scan" })
    }

    override fun onDestroy() { observer?.cancel(); wakeLock?.takeIf { it.isHeld }?.release(); super.onDestroy() }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ScanManager.pause()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.noway.edge.START"
        const val ACTION_RESUME = "com.noway.edge.RESUME"
        const val ACTION_PAUSE = "com.noway.edge.PAUSE"
        const val ACTION_STOP = "com.noway.edge.STOP"
        private const val CHANNEL_ID = "noway_authorized_scan"
        private const val NOTIFICATION_ID = 5105
    }
}
