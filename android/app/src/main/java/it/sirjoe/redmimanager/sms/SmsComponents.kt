package it.sirjoe.redmimanager.sms

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import it.sirjoe.redmimanager.AppLog

/*
 * Android permette di scrivere gli SMS solo all'app SMS predefinita.
 * Durante il ripristino Redmi Manager lo diventa per pochi minuti: questi componenti
 * servono solo a quello. Gli SMS che arrivano in quei minuti vengono salvati
 * nell'archivio dei messaggi, cosi' nessun messaggio va perso.
 */

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (parts.isEmpty()) return
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, parts[0].originatingAddress ?: "")
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, parts[0].timestampMillis)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
            AppLog(ctx).add("SMS arrivato durante il ripristino da ${parts[0].originatingAddress}: salvato")
        } catch (e: Exception) {
            AppLog(ctx).add("SMS arrivato durante il ripristino non salvato: ${e.message}")
        }
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AppLog(ctx).add("MMS arrivato mentre Redmi Manager era app SMS predefinita: rimetti la tua app Messaggi")
    }
}

/** Se qualcuno prova a scrivere un SMS mentre Redmi Manager e' l'app predefinita */
class ComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "Redmi Manager non invia SMS: rimetti la tua app Messaggi come predefinita",
            Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
        }
        finish()
    }
}

class RespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
