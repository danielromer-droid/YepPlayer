package com.yepplayer.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records a live channel (MPEG-TS) or downloads a film/episode to the app's own folder
 * (Android/data/com.yepplayer.app/files/Movies/YepPlayer). Runs as a foreground service with a
 * notification, so it continues when the app is in the background.
 */
class RecordingService : Service() {
    class Job(val id: Int, val name: String, val file: File, val live: Boolean, val endAt: Long) {
        @Volatile var bytes = 0L
        @Volatile var stop = false
        @Volatile var error: String? = null
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 42
        private val ids = AtomicInteger(1)
        val jobs = ConcurrentHashMap<Int, Job>()

        fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "YepPlayer").apply { mkdirs() }

        fun isRecording(file: File) = jobs.values.any { it.file == file }

        /** Live: [minutes] > 0. Download (film/episode): minutes = 0. */
        fun start(activity: Activity, url: String, name: String, minutes: Int) {
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
            activity.startForegroundService(Intent(activity, RecordingService::class.java).setAction("start")
                .putExtra("url", url).putExtra("name", name).putExtra("minutes", minutes))
        }

        fun stop(ctx: Context, id: Int) { jobs[id]?.stop = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> {
                goForeground()
                val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
                val name = intent.getStringExtra("name") ?: "Recording"
                val minutes = intent.getIntExtra("minutes", 0)
                begin(url, name, minutes)
            }
            "stop_all" -> jobs.values.forEach { it.stop = true }
        }
        if (jobs.isEmpty()) finish()
        return START_NOT_STICKY
    }

    private fun begin(url: String, name: String, minutes: Int) {
        val live = minutes > 0
        val ext = if (live) "ts" else url.substringAfterLast('.', "mp4").substringBefore('?').take(5).ifBlank { "mp4" }
        val safe = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(60).ifBlank { "Recording" }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
        val file = File(dir(this), "$safe · $stamp.$ext")
        val job = Job(ids.getAndIncrement(), name, file, live, if (live) System.currentTimeMillis() + minutes * 60_000L else Long.MAX_VALUE)
        jobs[job.id] = job
        updateNotification()
        Thread({ run(job, url) }, "rec-${job.id}").start()
    }

    private fun run(job: Job, url: String) {
        NetGuard.load(getSharedPreferences("yepplayer", MODE_PRIVATE))
        var failures = 0
        var lastNotify = 0L
        FileOutputStream(job.file, true).use { out ->
            while (!job.stop && System.currentTimeMillis() < job.endAt) {
                try {
                    val conn = Source.open(url, 15000, 20000)
                    try {
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (!job.stop && System.currentTimeMillis() < job.endAt) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); job.bytes += n; failures = 0
                                val now = System.currentTimeMillis()
                                if (now - lastNotify > 5000) { lastNotify = now; updateNotification() }
                            }
                        }
                    } finally { conn.disconnect() }
                    if (!job.live) break // download finished
                } catch (e: Exception) {
                    job.error = e.message
                    if (e is NetGuard.Blocked || ++failures > 5) break
                    Thread.sleep(2000L * failures) // live stream dropped: reconnect
                }
            }
        }
        jobs.remove(job.id)
        if (job.bytes == 0L) job.file.delete()
        updateNotification()
        if (jobs.isEmpty()) finish()
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        channel()
        val list = jobs.values.sortedBy { it.id }
        val text = if (list.isEmpty()) "Finished" else list.joinToString("\n") { "${it.name} · ${it.bytes / (1024 * 1024)} MB" }
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction("stop_all"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("open", "recordings"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(if (list.size > 1) "Recording ${list.size} items" else "Recording")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(list.isNotEmpty()).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification() {
        if (jobs.isEmpty()) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) } catch (_: SecurityException) { }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
