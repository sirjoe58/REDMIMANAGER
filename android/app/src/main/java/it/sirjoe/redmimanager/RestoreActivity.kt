package it.sirjoe.redmimanager

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.provider.Telephony
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RestoreActivity : AppCompatActivity() {
    private lateinit var s: Settings
    private lateinit var log: AppLog
    private lateinit var content: LinearLayout
    private lateinit var area: LinearLayout
    private var dest: Dest? = null
    private var engine: RestoreEngine? = null
    @Volatile private var stop = false

    private var waitResult: CompletableDeferred<Unit>? = null
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitResult?.complete(Unit)
    }

    /** Apre una schermata e aspetta che l'utente torni indietro */
    private suspend fun launchAndWait(i: Intent) {
        val d = CompletableDeferred<Unit>()
        waitResult = d
        try {
            launcher.launch(i)
            d.await()
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        log = AppLog(this)
        content = Ui.page(this, "Ripristina")
        area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(area)
        stepSource()
    }

    override fun onDestroy() {
        super.onDestroy()
        stop = true
        val d = dest
        if (d != null) Thread { d.close() }.start()
    }

    private fun clear(): LinearLayout { area.removeAllViews(); return area }

    // ---------------------------------------------------------------- 1) da dove

    private fun stepSource() {
        val c = clear()
        Ui.section(c, "1. Da dove vuoi ripristinare?")
        val options = mutableListOf<Pair<String, String>>()
        if (s.nasPath.isNotBlank()) options += "nas" to "NAS (${s.nasPath})"
        if (s.usbUri.isNotBlank()) options += "usb" to "Chiavetta/cartella (${s.usbLabel})"
        if (s.pcToken.isNotBlank()) options += "pc" to "PC ${s.pcName}"
        if (options.isEmpty()) {
            Ui.text(c, "Prima indica nelle Impostazioni dove si trovano i backup: NAS, chiavetta USB o PC.")
            Ui.button(c, "Apri le Impostazioni") { startActivity(Intent(this, SettingsActivity::class.java)); finish() }
            return
        }
        Ui.note(c, "Su un telefono nuovo: installa Redmi Manager, concedi i permessi, imposta NAS, chiavetta o PC come sul telefono vecchio e torna qui.")
        for ((key, label) in options) Ui.button(c, label, Ui.BLUE) { openSource(key) }
    }

    private fun openSource(key: String) {
        val c = clear()
        Ui.text(c, "Mi collego…")
        Ui.progress(c)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val d = Dest.byKey(this@RestoreActivity, s, key) ?: throw IllegalStateException("Destinazione non configurata")
                    d.check()
                    val e = RestoreEngine(this@RestoreActivity, d, log)
                    dest?.close()
                    dest = d; engine = e
                    Result.success(e.phones())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            r.onSuccess { stepPhone(it) }.onFailure {
                stepSource()
                Ui.message(this@RestoreActivity, "Non raggiungibile", Util.shortError(it))
            }
        }
    }

    // ---------------------------------------------------------------- 2) telefono e data

    private fun stepPhone(phones: List<String>) {
        val c = clear()
        Ui.section(c, "2. Quale telefono?")
        if (phones.isEmpty()) {
            Ui.text(c, "In questa destinazione non ci sono backup di Redmi Manager.")
            Ui.button(c, "Indietro", Ui.BLUE, outlined = true) { stepSource() }
            return
        }
        for (p in phones) Ui.button(c, if (p == s.deviceName) "$p (questo telefono)" else p, Ui.BLUE) { loadSnapshots(p) }
        Ui.button(c, "Indietro", Ui.BLUE, outlined = true) { stepSource() }
    }

    private fun loadSnapshots(phone: String) {
        val c = clear()
        Ui.text(c, "Leggo le date dei backup…")
        Ui.progress(c)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { try { Result.success(engine!!.snapshots(phone)) } catch (e: Exception) { Result.failure(e) } }
            r.onSuccess { stepSnapshot(phone, it) }.onFailure { Ui.message(this@RestoreActivity, "Errore", Util.shortError(it)); stepSource() }
        }
    }

    private fun stepSnapshot(phone: String, snaps: List<RestoreEngine.Snapshot>) {
        val c = clear()
        Ui.section(c, "3. Di quale data?")
        Ui.note(c, "Telefono: $phone")
        if (snaps.isEmpty()) {
            Ui.text(c, "Non ci sono copie dei dati: puoi comunque ripristinare foto e file.")
            Ui.button(c, "Ripristina solo foto e file", Ui.BLUE) { stepWhat(phone, null) }
        }
        for (sn in snaps) {
            val counts = sn.info?.optJSONObject("conteggi")
            val det = if (counts != null) listOfNotNull(
                counts.optInt("contatti", -1).takeIf { it >= 0 }?.let { "$it contatti" },
                counts.optInt("sms", -1).takeIf { it >= 0 }?.let { "$it SMS" },
                counts.optInt("app", -1).takeIf { it >= 0 }?.let { "$it app" }
            ).joinToString(", ") else ""
            val label = sn.date + (if (!sn.complete) "  (incompleto)" else "") + if (det.isNotEmpty()) "\n$det" else ""
            Ui.button(c, label, if (sn.complete) Ui.BLUE else Ui.RED, outlined = !sn.complete) { stepWhat(phone, sn) }
        }
        Ui.button(c, "Indietro", Ui.BLUE, outlined = true) { lifecycleScope.launch { stepPhone(withContext(Dispatchers.IO) { engine!!.phones() }) } }
    }

    // ---------------------------------------------------------------- 4) cosa ripristinare

    private fun stepWhat(phone: String, snap: RestoreEngine.Snapshot?) {
        val c = clear()
        Ui.section(c, "4. Cosa vuoi ripristinare?")
        Ui.note(c, "Telefono: $phone" + (snap?.let { " – copia del ${it.date}" } ?: ""))
        val brand = Brands.get(snap?.info?.optString("marca")?.takeIf { it.isNotEmpty() } ?: s.brand)
        val checks = LinkedHashMap<String, MaterialCheckBox>()
        fun add(key: String, label: String, on: Boolean) { checks[key] = Ui.check(c, label, on) }
        if (snap != null) {
            add("contatti", "Rubrica (contatti)", true)
            add("sms", "SMS", true)
            add("chiamate", "Registro chiamate", true)
            add("calendario", "Calendario", true)
            add("app", "App (una conferma per ogni app)", true)
        }
        add("file", "Foto, video, documenti, WhatsApp e altri file", true)
        if (brand.factoryPaths.isNotEmpty()) add("fabbrica", "Backup di fabbrica ${brand.name} (dati delle app)", false)
        Ui.note(c, "Consiglio: ripristina prima i file e WhatsApp, poi reinstalla WhatsApp e scegli \"Ripristina\" dal backup locale.")
        Ui.button(c, "Inizia il ripristino", Ui.ORANGE) {
            val sel = checks.filterValues { it.isChecked }.keys
            if (sel.isEmpty()) return@button
            Ui.confirm(this, "Confermi?", "I dati verranno aggiunti a quelli già presenti sul telefono. Continuo?", "Ripristina") {
                run(phone, snap, sel, brand)
            }
        }
        Ui.button(c, "Indietro", Ui.BLUE, outlined = true) { loadSnapshots(phone) }
    }

    // ---------------------------------------------------------------- esecuzione

    private lateinit var pTitle: TextView
    private lateinit var pDetail: TextView
    private lateinit var pBar: LinearProgressIndicator
    private lateinit var report: TextView

    private fun progress(t: String, d: String = "", p: Int = -1) = runOnUiThread {
        pTitle.text = t; pDetail.text = d
        if (p >= 0) { pBar.isIndeterminate = false; pBar.setProgressCompat(p, true) } else pBar.isIndeterminate = true
    }

    private fun addReport(line: String) = runOnUiThread {
        report.append(line + "\n")
        log.add("Ripristino: $line")
    }

    private fun run(phone: String, snap: RestoreEngine.Snapshot?, sel: Set<String>, brand: Brand) {
        val c = clear()
        Ui.section(c, "Ripristino in corso")
        pTitle = Ui.text(c, "", 17f)
        pDetail = Ui.note(c, "")
        pBar = Ui.progress(c)
        report = Ui.text(c, "")
        val stopBtn = Ui.button(c, "Ferma", Ui.RED, outlined = true) { stop = true }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stop = false
        val e = engine!!
        lifecycleScope.launch {
            try {
                // 1) file (prima di tutto: contengono anche WhatsApp e il backup di fabbrica)
                if ("file" in sel || "fabbrica" in sel) {
                    val roots = if ("file" in sel) emptyList() else brand.factoryPaths
                    val (copied, present) = withContext(Dispatchers.IO) {
                        e.restoreFiles(phone, roots, { t, d, p -> progress(t, d, p) }, { stop })
                    }
                    addReport("File: $copied ripristinati, $present già presenti")
                }
                if (snap != null && !stop) {
                    // 2) chiamate
                    if ("chiamate" in sel) step("Registro chiamate") {
                        val f = e.dataFile(phone, snap.name, "chiamate.json") ?: return@step "non presente nel backup"
                        val (a, k) = DataImport.calls(this@RestoreActivity, f) { progress("Registro chiamate…", "$it") }
                        "$a ripristinate, $k già presenti"
                    }
                    // 3) calendario
                    if ("calendario" in sel && !stop) step("Calendario") {
                        val f = e.dataFile(phone, snap.name, "calendario.json") ?: return@step "non presente nel backup"
                        val (a, k) = DataImport.calendar(this@RestoreActivity, f) { progress("Calendario…", "$it") }
                        "$a eventi ripristinati nel calendario \"Redmi Manager (ripristino)\", $k già presenti"
                    }
                    // 4) SMS
                    if ("sms" in sel && !stop) restoreSms(phone, snap)
                    // 5) rubrica
                    if ("contatti" in sel && !stop) restoreContacts(phone, snap)
                    // 6) app
                    if ("app" in sel && !stop) restoreApps(phone, snap)
                }
                progress(if (stop) "Ripristino interrotto" else "Ripristino completato ✓", "", 100)
                if ("fabbrica" in sel && !stop) factoryHint(brand)
            } catch (ex: Exception) {
                progress("Ripristino non riuscito", Util.shortError(ex), 0)
                addReport("ERRORE: ${Util.shortError(ex)}")
            } finally {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stopBtn.visibility = View.GONE
                Ui.button(c, "Chiudi", Ui.BLUE) { finish() }
            }
        }
    }

    private suspend fun step(name: String, block: suspend () -> String) {
        progress("$name…")
        val r = withContext(Dispatchers.IO) { try { block() } catch (e: Exception) { "errore: ${Util.shortError(e)}" } }
        addReport("$name: $r")
    }

    private fun isDefaultSms(): Boolean = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    private suspend fun restoreSms(phone: String, snap: RestoreEngine.Snapshot) {
        val f = withContext(Dispatchers.IO) { try { engine!!.dataFile(phone, snap.name, "sms.json") } catch (_: Exception) { null } }
        if (f == null) { addReport("SMS: non presenti nel backup"); return }
        val previous = Telephony.Sms.getDefaultSmsPackage(this)
        if (!isDefaultSms()) {
            val ok = CompletableDeferred<Boolean>()
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Ripristino SMS")
                    .setMessage("Per rimettere gli SMS, Android chiede che Redmi Manager diventi per qualche minuto l'app SMS predefinita.\n\n" +
                        "Nella prossima schermata scegli Redmi Manager. Alla fine ti riporto a rimettere la tua app Messaggi.")
                    .setCancelable(false)
                    .setPositiveButton("Continua") { _, _ -> ok.complete(true) }
                    .setNegativeButton("Salta gli SMS") { _, _ -> ok.complete(false) }
                    .show()
            }
            if (!ok.await()) { addReport("SMS: saltati"); return }
            val i = if (Build.VERSION.SDK_INT >= 29) {
                getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            launchAndWait(i)
            if (!isDefaultSms()) { addReport("SMS: non ripristinati (Redmi Manager non è stata scelta come app SMS)"); return }
        }
        step("SMS") {
            val (a, k) = DataImport.sms(this@RestoreActivity, f) { progress("SMS…", "$it") }
            "$a ripristinati, $k già presenti"
        }
        // rimettere l'app Messaggi
        val back = CompletableDeferred<Unit>()
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("Rimetti la tua app Messaggi")
                .setMessage("Ora scegli di nuovo la tua app dei messaggi come app SMS predefinita, altrimenti non riceverai le notifiche dei nuovi SMS.")
                .setCancelable(false)
                .setPositiveButton("Apri") { _, _ -> back.complete(Unit) }
                .show()
        }
        back.await()
        val i = if (Build.VERSION.SDK_INT < 29 && previous != null)
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, previous)
        else Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        launchAndWait(i)
        if (isDefaultSms()) addReport("ATTENZIONE: Redmi Manager è ancora l'app SMS predefinita: cambiala in Impostazioni › App › App predefinite › SMS")
    }

    private suspend fun restoreContacts(phone: String, snap: RestoreEngine.Snapshot) {
        progress("Rubrica…")
        val f = withContext(Dispatchers.IO) { try { engine!!.dataFile(phone, snap.name, "contatti.vcf") } catch (_: Exception) { null } }
        if (f == null || f.length() == 0L) { addReport("Rubrica: non presente nel backup"); return }
        val ok = CompletableDeferred<Unit>()
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("Ripristino rubrica")
                .setMessage("Si apre l'app Contatti del telefono: conferma l'importazione (scegli dove salvarli: \"Telefono\" per non usare Google).")
                .setCancelable(false)
                .setPositiveButton("Apri Contatti") { _, _ -> ok.complete(Unit) }
                .show()
        }
        ok.await()
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x-vcard")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            launchAndWait(i)
            addReport("Rubrica: aperta nell'app Contatti per l'importazione")
        } catch (e: Exception) {
            addReport("Rubrica: nessuna app Contatti in grado di importare (${Util.shortError(e)})")
        }
    }

    private suspend fun restoreApps(phone: String, snap: RestoreEngine.Snapshot) {
        val e = engine!!
        progress("App: leggo l'elenco…")
        val apps = withContext(Dispatchers.IO) { try { e.savedApps(phone, snap.name) } catch (_: Exception) { emptyList() } }
        val missing = apps.filter { !e.installed(it) }
        if (missing.isEmpty()) { addReport("App: tutte già installate (${apps.size})"); return }

        // scelta delle app
        val chosen = CompletableDeferred<List<RestoreEngine.SavedApp>>()
        runOnUiThread {
            val sel = BooleanArray(missing.size) { true }
            AlertDialog.Builder(this).setTitle("App da reinstallare (${missing.size})")
                .setMultiChoiceItems(missing.map { it.name }.toTypedArray(), sel) { _, w, c -> sel[w] = c }
                .setCancelable(false)
                .setPositiveButton("Installa") { _, _ -> chosen.complete(missing.filterIndexed { i, _ -> sel[i] }) }
                .setNegativeButton("Salta") { _, _ -> chosen.complete(emptyList()) }
                .show()
        }
        val list = chosen.await()
        if (list.isEmpty()) { addReport("App: saltate"); return }

        if (!Installer.canInstall(this)) {
            val ok = CompletableDeferred<Unit>()
            runOnUiThread {
                Ui.message(this, "Permesso di installazione",
                    "Nella schermata che si apre attiva \"Consenti da questa fonte\" per Redmi Manager, poi torna indietro.") { ok.complete(Unit) }
            }
            ok.await()
            launchAndWait(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            if (!Installer.canInstall(this)) { addReport("App: permesso di installazione non concesso"); return }
        }
        var ok = 0
        var ko = 0
        for ((i, a) in list.withIndex()) {
            if (stop) break
            progress("App ${i + 1} di ${list.size}", a.name, i * 100 / list.size)
            val files = withContext(Dispatchers.IO) { try { e.downloadApp(phone, a) } catch (ex: Exception) { null } }
            if (files.isNullOrEmpty()) { ko++; addReport("App ${a.name}: file non trovati"); continue }
            val (done, msg) = Installer.install(this, files)
            if (done) ok++ else { ko++; addReport("App ${a.name}: $msg") }
        }
        addReport("App: $ok installate" + if (ko > 0) ", $ko non installate" else "")
    }

    private fun factoryHint(brand: Brand) {
        AlertDialog.Builder(this).setTitle("Backup di fabbrica – ${brand.name}")
            .setMessage("I file del backup di fabbrica sono di nuovo sul telefono.\n\n" + brand.howToRestore)
            .setPositiveButton("Apri") { _, _ -> Brands.openFactoryBackup(this, brand) }
            .setNegativeButton("Chiudi", null)
            .show()
    }
}
