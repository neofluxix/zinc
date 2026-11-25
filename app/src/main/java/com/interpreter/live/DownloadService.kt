package com.interpreter.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service so pack downloads keep going after the app is closed. */
class DownloadService : Service() {

    private val channel = "zinc_packs"
    private val nid = 7

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, pct: Int): Notification =
        Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Zinc offline packs")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, pct == 0)
            .build()

    private fun update(text: String, pct: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(nid, notif(text, pct))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Offline packs", NotificationManager.IMPORTANCE_LOW))
        val first = notif("Getting ready…", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(nid, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(nid, first)

        if (Packs.busy) return START_NOT_STICKY
        Packs.busy = true
        Packs.cancel = false
        val mode = intent?.getStringExtra("mode") ?: "ensure"
        val c1 = intent?.getStringExtra("c1") ?: "en"
        val c2 = intent?.getStringExtra("c2") ?: "es"
        val wifi = intent?.getBooleanExtra("wifi", false) ?: false

        Thread {
            try {
                if (mode == "all") Packs.all(this, wifi) { t, p -> update(t, p) }
                else Packs.ensure(this, c1, c2, wifi) { t, p -> update(t, p) }
            } finally {
                Packs.busy = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
