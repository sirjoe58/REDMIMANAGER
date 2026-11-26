package it.sirjoe.redmimanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Backup in background.
 * - Ogni 15 minuti (quando c'e' una rete) controlla se NAS, PC o chiavetta sono raggiungibili
 *   e, appena li vede, fa il backup se e' passato l'intervallo scelto (come fa Google di notte).
 * - "Backup adesso" usa lo stesso lavoro con force=true.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val AUTO = "backup_automatico"
        private const val NOW = "backup_adesso"
        private const val CHANNEL = "backup"
        private const val NOTIF_ID = 4710

        fun schedule(ctx: Context) {
            val s = Settings(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (!s.autoEnabled || !s.anyDestination()) {
                wm.cancelUniqueWork(AUTO); return
            }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun startNow(ctx: Context) {
            Status.cancel = false
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf("force" to true))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun stop(ctx: Context) {
            Status.cancel = true
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        }

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun isCharging(ctx: Context): Boolean {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }

    private fun notification(text: String, ongoing: Boolean, percent: Int = -1): android.app.Notification {
        channel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Redmi Manager")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (ongoing) setProgress(100, maxOf(0, percent), percent < 0) }
            .build()
    }

    private fun foregroundInfo(text: String, percent: Int = -1): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, notification(text, true, percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notification(text, true, percent))

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Backup in corso…")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings(applicationContext)
        val log = AppLog(applicationContext)
        if (s.disclaimerAccepted < Settings.DISCLAIMER_VERSION) return@withContext Result.success()
        val force = inputData.getBoolean("force", false)
        if (Status.running) return@withContext Result.success()
        if (!force && Status.starting) return@withContext Result.success()

        var dests = Dest.enabled(applicationContext, s)
        if (!force) {
            if (!s.autoEnabled) return@withContext Result.success()
            if (s.onlyCharging && !isCharging(applicationContext)) return@withContext Result.success()
            val due = System.currentTimeMillis() - s.autoHours * 3_600_000L
            dests = dests.filter { s.lastBackup(it.key) < due }
            // controllo veloce: si parte solo se almeno una destinazione e' raggiungibile
            dests = dests.filter { d ->
                val ok = try { d.check(); true } catch (_: Exception) { false }
                if (!ok) d.close()
                ok
            }
            if (dests.isEmpty()) return@withContext Result.success()
            log.add("Backup automatico: " + dests.joinToString(", ") { it.label } + " raggiungibile")
        }
        if (dests.isEmpty()) {
            Status.starting = false
            s.lastResult = "Nessuna destinazione attiva: scegli NAS, chiavetta o PC nelle Impostazioni"
            return@withContext Result.success()
        }

        try {
            setForeground(foregroundInfo("Backup in corso…"))
        } catch (e: Exception) {
            // Android a volte non permette il primo piano dal background: si prosegue lo stesso
            log.add("Avviso: backup senza notifica fissa (${Util.shortError(e)})")
        }

        // aggiorna la notifica con l'avanzamento
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val ticker = Thread {
            try {
                while (true) {
                    Thread.sleep(2000)
                    if (Status.title.isNotEmpty())
                        nm.notify(NOTIF_ID, notification("${Status.title}\n${Status.detail}", true, Status.percent))
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }

        val r = try {
            BackupEngine(applicationContext, s, log) { isStopped || Status.cancel }.run(dests)
        } finally {
            ticker.interrupt()
        }
        nm.notify(NOTIF_ID + 1, notification(r.summary, false))
        Result.success()
    }
}
