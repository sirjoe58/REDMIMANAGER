package it.sirjoe.redmimanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** Esporta i dati del telefono (rubrica, SMS, chiamate, calendario, elenco app) in file */
object DataExport {

    private fun has(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Rubrica in formato vCard (.vcf), leggibile da qualsiasi telefono. Restituisce il numero di contatti. */
    fun contacts(ctx: Context, out: File): Int {
        if (!has(ctx, android.Manifest.permission.READ_CONTACTS)) throw SecurityException("permesso Contatti mancante")
        val cr = ctx.contentResolver
        val keys = mutableListOf<String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { keys += it }
        }
        var n = 0
        FileOutputStream(out).use { fo ->
            for (k in keys) {
                try {
                    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, k)
                    cr.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        afd.createInputStream().use { it.copyTo(fo) }
                        n++
                    }
                } catch (_: Exception) {
                }
            }
        }
        return n
    }

    private inline fun json(out: File, block: (JsonWriter) -> Unit) {
        JsonWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { w ->
            w.setIndent(" ")
            block(w)
        }
    }

    /** SMS (ricevuti, inviati, bozze). Restituisce il numero di messaggi. */
    fun sms(ctx: Context, out: File): Int {
        if (!has(ctx, android.Manifest.permission.READ_SMS)) throw SecurityException("permesso SMS mancante")
        var n = 0
        json(out) { w ->
            w.beginArray()
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
                    Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SEEN),
                null, null, Telephony.Sms.DATE + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    w.beginObject()
                    w.name("address").value(c.getString(0) ?: "")
                    w.name("body").value(c.getString(1) ?: "")
                    w.name("date").value(c.getLong(2))
                    w.name("date_sent").value(c.getLong(3))
                    w.name("type").value(c.getInt(4))
                    w.name("read").value(c.getInt(5))
                    w.name("seen").value(c.getInt(6))
                    w.endObject()
                    n++
                }
            }
            w.endArray()
        }
        return n
    }

    /** Registro chiamate. Restituisce il numero di chiamate. */
    fun calls(ctx: Context, out: File): Int {
        if (!has(ctx, android.Manifest.permission.READ_CALL_LOG)) throw SecurityException("permesso Registro chiamate mancante")
        var n = 0
        json(out) { w ->
            w.beginArray()
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME),
                null, null, CallLog.Calls.DATE + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    w.beginObject()
                    w.name("number").value(c.getString(0) ?: "")
                    w.name("date").value(c.getLong(1))
                    w.name("duration").value(c.getLong(2))
                    w.name("type").value(c.getInt(3))
                    w.name("name").value(c.getString(4) ?: "")
                    w.endObject()
                    n++
                }
            }
            w.endArray()
        }
        return n
    }

    /** Eventi del calendario (di tutti i calendari presenti sul telefono). */
    fun calendar(ctx: Context, out: File): Int {
        if (!has(ctx, android.Manifest.permission.READ_CALENDAR)) throw SecurityException("permesso Calendario mancante")
        val cols = arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
            CalendarContract.Events.EXDATE, CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME
        )
        var n = 0
        json(out) { w ->
            w.beginArray()
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, cols,
                CalendarContract.Events.DELETED + "=0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    w.beginObject()
                    w.name("title").value(c.getString(0) ?: "")
                    w.name("description").value(c.getString(1) ?: "")
                    w.name("location").value(c.getString(2) ?: "")
                    w.name("dtstart").value(c.getLong(3))
                    w.name("dtend").value(if (c.isNull(4)) 0L else c.getLong(4))
                    w.name("duration").value(c.getString(5) ?: "")
                    w.name("all_day").value(c.getInt(6))
                    w.name("rrule").value(c.getString(7) ?: "")
                    w.name("rdate").value(c.getString(8) ?: "")
                    w.name("exdate").value(c.getString(9) ?: "")
                    w.name("timezone").value(c.getString(10) ?: "")
                    w.name("calendar").value(c.getString(11) ?: "")
                    w.endObject()
                    n++
                }
            }
            w.endArray()
        }
        return n
    }

    data class AppInfo(
        val pkg: String, val name: String, val versionName: String, val versionCode: Long,
        val apks: List<File>
    ) {
        /** cartella dell'app nei backup: App/<pacchetto>_<versione> */
        val folder get() = "${pkg}_$versionCode"
    }

    /** App installate dall'utente (comprese le app di sistema aggiornate dal Play Store/store) */
    fun installedApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        return list.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if ((system && !updated) || pi.packageName == ctx.packageName) return@mapNotNull null
            val files = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()))
                .filterNotNull().map { File(it) }.filter { it.canRead() }
            if (files.isEmpty()) return@mapNotNull null
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            AppInfo(pi.packageName, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "", vc, files)
        }.sortedBy { it.name.lowercase() }
    }

    fun appsJson(apps: List<AppInfo>, out: File) {
        json(out) { w ->
            w.beginArray()
            for (a in apps) {
                w.beginObject()
                w.name("package").value(a.pkg)
                w.name("name").value(a.name)
                w.name("version").value(a.versionName)
                w.name("version_code").value(a.versionCode)
                w.name("folder").value(a.folder)
                w.name("apks").beginArray()
                a.apks.forEach { w.value(apkName(it)) }
                w.endArray()
                w.endObject()
            }
            w.endArray()
        }
    }

    /** base.apk / split_config.arm64_v8a.apk ... */
    fun apkName(f: File): String = Util.safeName(f.name)
}
