package pro.cslauncher.app.install

import android.app.Notification
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

/** Foreground (dataSync) service that keeps installations running in the background and shows their progress. */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(title: String, text: String, progress: Float?): Notification =
        NotificationCompat.Builder(this, CsApp.CHANNEL_INSTALL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .apply { if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true) }.build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 7, build("Preparing installation", "", null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (!started) {
            started = true
            val nm = getSystemService(android.app.NotificationManager::class.java)
            scope.launch {
                InstallManager.jobs.collectLatest { jobs ->
                    val active = jobs.values.filter { it.active }
                    if (active.isEmpty()) { delay(500); if (!InstallManager.anyActive()) { ServiceCompat.stopForeground(this@InstallService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }; return@collectLatest }
                    val j = active.first()
                    nm.notify(7, build("Installing ${j.versionId}" + if (active.size > 1) " (+${active.size - 1})" else "", "${j.stage} · ${j.finishedFiles}/${j.totalFiles}", j.fraction))
                    delay(750)   // throttle notification updates
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
