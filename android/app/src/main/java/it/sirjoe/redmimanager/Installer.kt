package it.sirjoe.redmimanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Installa le app salvate (anche quelle divise in piu' file .apk).
 * Android chiede conferma all'utente per ogni app.
 */
object Installer {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Pair<Boolean, String>>>()

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Restituisce (installata, messaggio). Aspetta al massimo 10 minuti la conferma dell'utente. */
    suspend fun install(ctx: Context, apks: List<File>): Pair<Boolean, String> {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = pi.createSession(params)
        val done = CompletableDeferred<Pair<Boolean, String>>()
        waiting[id] = done
        try {
            withContext(Dispatchers.IO) { pi.openSession(id).use { session ->
                for (f in apks) {
                    f.inputStream().use { input ->
                        session.openWrite(f.name, 0, f.length()).use { out ->
                            input.copyTo(out, 256 * 1024)
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java).putExtra("session", id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val p = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(p.intentSender)
            } }
            return withTimeoutOrNull(10 * 60_000L) { done.await() } ?: (false to "nessuna risposta")
        } catch (e: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            return false to Util.shortError(e)
        } finally {
            waiting.remove(id)
        }
    }

    internal fun finished(id: Int, ok: Boolean, msg: String) {
        waiting[id]?.complete(ok to msg)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("session", -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                    } catch (e: Exception) {
                        Installer.finished(id, false, Util.shortError(e))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.finished(id, true, "installata")
            else -> Installer.finished(
                id, false,
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore $status").let {
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "annullata" else it
                }
            )
        }
    }
}
