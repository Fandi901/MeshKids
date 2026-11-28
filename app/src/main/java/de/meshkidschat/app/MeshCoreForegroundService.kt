package de.meshkidschat.app

import de.meshkidschat.app.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MeshCoreForegroundService : Service() {

    companion object {
        const val CHANNEL_ID_CONNECTION = "meshkids_connection_channel"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val ACTION_START = "com.example.meshkids.action.START_FOREGROUND_SERVICE"
        const val ACTION_STOP = "com.example.meshkids.action.STOP_FOREGROUND_SERVICE"

        @Volatile
        private var sharedManagerInstance: MeshCoreBleManager? = null

        fun getOrCreateSharedManager(context: Context): MeshCoreBleManager {
            val existing = sharedManagerInstance
            if (existing != null) return existing
            synchronized(this) {
                val again = sharedManagerInstance
                if (again != null) return again
                val created = MeshCoreBleManager(context.applicationContext)
                sharedManagerInstance = created
                return created
            }
        }

        fun startService(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MeshCoreForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val appCtx = context.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, MeshCoreForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MeshCoreForegroundService = this@MeshCoreForegroundService
        fun getBleManager(): MeshCoreBleManager = bleManager
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusObserverJob: Job? = null
    lateinit var bleManager: MeshCoreBleManager
        private set

    override fun onCreate() {
        super.onCreate()
        bleManager = getOrCreateSharedManager(applicationContext)
        ensureConnectionNotificationChannel()
        promoteToForeground(buildConnectionNotification("Funkgerät wird vorbereitet...", false))
        observeConnectionStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!::bleManager.isInitialized) {
            bleManager = getOrCreateSharedManager(applicationContext)
        }
        ensureConnectionNotificationChannel()
        val isConn = bleManager.isConnected.value
        val stateText = if (isConn) "Funkgerät bereit" else bleManager.connectionState.value
        promoteToForeground(buildConnectionNotification(stateText, isConn))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        statusObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeConnectionStatus() {
        statusObserverJob?.cancel()
        statusObserverJob = serviceScope.launch {
            combine(bleManager.isConnected, bleManager.connectionState) { connected, state ->
                Pair(connected, state)
            }.collect { (connected, state) ->
                val text = if (connected) "Funkgerät bereit" else state
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID_FOREGROUND, buildConnectionNotification(text, connected))
            }
        }
    }

    private fun ensureConnectionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_CONNECTION,
                "Funkgerät-Verbindung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält die Verbindung zum MeshCore-Funkgerät im Hintergrund aktiv"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildConnectionNotification(statusText: String, isConnected: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (isConnected) "Verbunden · $statusText" else statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        }
    }
}
