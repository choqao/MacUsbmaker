package com.example.macusb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Keeps the process and CPU alive while a long download/flash is running. */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Working", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mac USB Builder")
            .setContentText("Working - keep the USB drive connected")
            .setOngoing(true)
            .build()
        startForeground(1, n)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "macusb:work").apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CH = "work"
        fun start(c: Context) = c.startForegroundService(Intent(c, KeepAliveService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, KeepAliveService::class.java)) }
    }
}
