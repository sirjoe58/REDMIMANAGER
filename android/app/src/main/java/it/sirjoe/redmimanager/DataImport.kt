package it.sirjoe.redmimanager

import android.accounts.Account
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Telephony
import android.util.JsonReader
import java.io.File
import java.io.InputStreamReader
import java.util.TimeZone

/** Rimette sul telefono SMS, chiamate e calendario salvati da Redmi Manager */
object DataImport {

    private fun readArray(f: File, each: (Map<String, Any?>) -> Unit) {
        JsonReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                val m = HashMap<String, Any?>()
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    m[k] = when (r.peek()) {
                        android.util.JsonToken.NUMBER -> r.nextString().toLongOrNull()
                        android.util.JsonToken.NULL -> { r.nextNull(); null }
                        android.util.JsonToken.BOOLEAN -> r.nextBoolean()
                        android.util.JsonToken.STRING -> r.nextString()
                        else -> { r.skipValue(); null }
                    }
                }
                r.endObject()
                each(m)
            }
            r.endArray()
        }
    }

    private fun Map<String, Any?>.s(k: String) = (this[k] as? String) ?: ""
    private fun Map<String, Any?>.l(k: String) = (this[k] as? Long) ?: 0L

    /**
     * SMS: Redmi Manager deve essere l'app SMS predefinita (Android lo richiede per scrivere i messaggi).
     * I messaggi gia' presenti (stessa data e numero) non vengono duplicati.
     * Restituisce (ripristinati, gia' presenti).
     */
    fun sms(ctx: Context, f: File, progress: (Int) -> Unit): Pair<Int, Int> {
        val cr = ctx.contentResolver
        val existing = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE), null, null, null)?.use { c ->
            while (c.moveToNext()) existing += "${c.getString(0)}|${c.getLong(1)}"
        }
        var added = 0
        var skipped = 0
        readArray(f) { m ->
            val key = "${m.s("address")}|${m.l("date")}"
            if (key in existing) { skipped++; return@readArray }
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, m.s("address"))
                put(Telephony.Sms.BODY, m.s("body"))
                put(Telephony.Sms.DATE, m.l("date"))
                put(Telephony.Sms.DATE_SENT, m.l("date_sent"))
                put(Telephony.Sms.TYPE, m.l("type").toInt())
                put(Telephony.Sms.READ, m.l("read").toInt())
                put(Telephony.Sms.SEEN, m.l("seen").toInt())
            }
            if (cr.insert(Telephony.Sms.CONTENT_URI, v) != null) {
                added++; existing += key
            }
            if ((added + skipped) % 50 == 0) progress(added + skipped)
        }
        return added to skipped
    }

    /** Registro chiamate. Restituisce (ripristinate, gia' presenti). */
    fun calls(ctx: Context, f: File, progress: (Int) -> Unit): Pair<Int, Int> {
        val cr = ctx.contentResolver
        val existing = HashSet<String>()
        cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, null)?.use { c ->
            while (c.moveToNext()) existing += "${c.getString(0)}|${c.getLong(1)}"
        }
        var added = 0
        var skipped = 0
        readArray(f) { m ->
            val key = "${m.s("number")}|${m.l("date")}"
            if (key in existing) { skipped++; return@readArray }
            val v = ContentValues().apply {
                put(CallLog.Calls.NUMBER, m.s("number"))
                put(CallLog.Calls.DATE, m.l("date"))
                put(CallLog.Calls.DURATION, m.l("duration"))
                put(CallLog.Calls.TYPE, m.l("type").toInt())
                put(CallLog.Calls.NEW, 0)
                if (m.s("name").isNotEmpty()) put(CallLog.Calls.CACHED_NAME, m.s("name"))
            }
            try {
                if (cr.insert(CallLog.Calls.CONTENT_URI, v) != null) { added++; existing += key }
            } catch (_: Exception) {
            }
            if ((added + skipped) % 50 == 0) progress(added + skipped)
        }
        return added to skipped
    }

    private const val CAL_NAME = "Redmi Manager (ripristino)"

    /** Calendario locale sul telefono dove finiscono gli eventi ripristinati */
    private fun localCalendar(ctx: Context): Long {
        val cr = ctx.contentResolver
        cr.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.ACCOUNT_TYPE + "=? AND " + CalendarContract.Calendars.NAME + "=?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, CAL_NAME), null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val acc = Account("Redmi Manager", CalendarContract.ACCOUNT_TYPE_LOCAL)
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, acc.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, acc.type)
            .build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, acc.name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, acc.type)
            put(CalendarContract.Calendars.NAME, CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFE8590C.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, acc.name)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val created: Uri = cr.insert(uri, v) ?: throw IllegalStateException("Impossibile creare il calendario")
        return created.lastPathSegment!!.toLong()
    }

    /** Eventi del calendario, in un calendario locale "Redmi Manager (ripristino)". */
    fun calendar(ctx: Context, f: File, progress: (Int) -> Unit): Pair<Int, Int> {
        val cr = ctx.contentResolver
        val calId = localCalendar(ctx)
        val existing = HashSet<String>()
        cr.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
            CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.DELETED + "=0",
            arrayOf(calId.toString()), null)?.use { c ->
            while (c.moveToNext()) existing += "${c.getString(0)}|${c.getLong(1)}"
        }
        var added = 0
        var skipped = 0
        readArray(f) { m ->
            val key = "${m.s("title")}|${m.l("dtstart")}"
            if (key in existing) { skipped++; return@readArray }
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, m.s("title"))
                put(CalendarContract.Events.DESCRIPTION, m.s("description"))
                put(CalendarContract.Events.EVENT_LOCATION, m.s("location"))
                put(CalendarContract.Events.DTSTART, m.l("dtstart"))
                put(CalendarContract.Events.ALL_DAY, m.l("all_day").toInt())
                put(CalendarContract.Events.EVENT_TIMEZONE, m.s("timezone").ifEmpty { TimeZone.getDefault().id })
                val rrule = m.s("rrule")
                if (rrule.isNotEmpty()) {
                    put(CalendarContract.Events.RRULE, rrule)
                    put(CalendarContract.Events.DURATION, m.s("duration").ifEmpty {
                        val dur = (m.l("dtend") - m.l("dtstart")).coerceAtLeast(0) / 1000
                        "P${dur}S"
                    })
                    if (m.s("rdate").isNotEmpty()) put(CalendarContract.Events.RDATE, m.s("rdate"))
                    if (m.s("exdate").isNotEmpty()) put(CalendarContract.Events.EXDATE, m.s("exdate"))
                } else {
                    val end = m.l("dtend").takeIf { it > 0 } ?: (m.l("dtstart") + 3_600_000L)
                    put(CalendarContract.Events.DTEND, end)
                }
            }
            try {
                if (cr.insert(CalendarContract.Events.CONTENT_URI, v) != null) { added++; existing += key }
            } catch (_: Exception) {
            }
            if ((added + skipped) % 50 == 0) progress(added + skipped)
        }
        return added to skipped
    }
}
