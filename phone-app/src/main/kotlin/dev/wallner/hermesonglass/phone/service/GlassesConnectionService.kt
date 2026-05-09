package dev.wallner.hermesonglass.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.wallner.hermesonglass.phone.HermesApp
import dev.wallner.hermesonglass.phone.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the glasses-link lifecycle in the foreground so the OS doesn't kill
 * it under Doze / aggressive memory pressure. Holds the WS↔Caps bridge and
 * watches `CapsLink.connected` to keep the persistent notification text in
 * sync.
 *
 * Hi Rokid AI app handles BLE pairing for us — there is no pair/reconnect
 * state machine on this side anymore.
 */
class GlassesConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(initialStateText()))
        val app = application as HermesApp
        app.capsLink.start()
        app.phoneToGlassesBridge.start()
        stateJob = scope.launch {
            app.capsLink.connected.collect { connected ->
                updateNotification(if (connected) "Glasses connected" else "Glasses offline — retrying")
            }
        }
        Timber.i("GlassesConnectionService started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        val app = application as HermesApp
        app.phoneToGlassesBridge.stop()
        app.capsLink.stop()
        super.onDestroy()
    }

    private fun initialStateText(): String = "Connecting…"

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Hermes on Glasses")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "hermes_glasses_connection"
        const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glasses connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Bluetooth + WebSocket lifecycle for the Rokid HUD"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, GlassesConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesConnectionService::class.java))
        }
    }
}
