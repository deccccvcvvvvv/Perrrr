package pro.cslauncher.app.install

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import pro.cslauncher.app.CsApp
import pro.cslauncher.app.ui.MainActivity

/**
 * Foreground (dataSync) service that keeps installations running in the background and shows their
 * progress. The notification always reflects the real [InstallJob] state: no synthetic percentages,
 * an indeterminate bar when the total size is unknown, and a separate failure notice with the
 * actual error so a failed install is not silently reported as finished.
 */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastFailureShownMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    /** Tapping the notification opens the Downloads screen (queued / active / finished / failed). */
    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 11, i, flags)
    }

    private fun build(title: String, text: String, progress: Float?, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(this, CsApp.CHANNEL_INSTALL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply {
                when {
                    progress != null -> setProgress(1000, (progress.coerceIn(0f, 1f) * 1000).toInt(), false)
                    ongoing -> setProgress(0, 0, true)   // size/file count unknown: indeterminate, never fake
                    else -> setProgress(0, 0, false)
                }
            }.build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 7, build("Preparing installation", "", null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (!started) {
            started = true
            val nm = getSystemService(android.app.NotificationManager::class.java)
            scope.launch {
                InstallManager.jobs.collectLatest { jobs ->
                    val active = jobs.values.filter { it.active }
                    if (active.isEmpty()) {
                        // Keep the failure visible: the foreground notification disappears when the
                        // service stops, so post a separate, dismissible notice with the real error.
                        val failed = jobs.values.filter { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }
                            .maxByOrNull { it.endedAtMs ?: 0L }
                        if (failed != null && (failed.endedAtMs ?: 0L) > lastFailureShownMs) {
                            lastFailureShownMs = failed.endedAtMs ?: 0L
                            val what = if (failed.status == JobStatus.CANCELLED) "Stopped" else "Failed"
                            nm.notify(8, build("$what: ${failed.title}",
                                "${failed.message.ifBlank { failed.stage }}. Open Downloads to retry.", null, ongoing = false))
                        }
                        delay(500)
                        if (!InstallManager.anyActive()) {
                            ServiceCompat.stopForeground(this@InstallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        return@collectLatest
                    }
                    val j = active.firstOrNull { it.status == JobStatus.RUNNING } ?: active.first()
                    val title = (if (j.kind == JobKind.TASK) j.title else "Minecraft ${j.title}") +
                        if (active.size > 1) "  (+${active.size - 1} queued)" else ""
                    val status = when (j.status) {
                        JobStatus.QUEUED -> "Queued"
                        JobStatus.RUNNING -> j.stage
                        else -> j.status.name
                    }
                    val counts = if (j.totalFiles > 0) " · ${j.finishedFiles}/${j.totalFiles} files" else ""
                    val detail = j.message.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    nm.notify(7, build(title, "$status$counts$detail", j.fraction))
                    delay(750)   // throttle notification updates
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
