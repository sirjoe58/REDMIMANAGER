package it.sirjoe.redmimanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var s: Settings
    private lateinit var permCard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var destText: TextView
    private lateinit var runBox: LinearLayout
    private lateinit var runTitle: TextView
    private lateinit var runDetail: TextView
    private lateinit var runBar: LinearProgressIndicator
    private lateinit var backupBtn: MaterialButton
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        val RUNTIME_PERMS: Array<String>
            get() = buildList {
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.WRITE_CONTACTS)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.WRITE_CALL_LOG)
                add(Manifest.permission.READ_CALENDAR)
                add(Manifest.permission.WRITE_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT < 30) add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        if (!BackupEngine.hasFileAccess(this)) askFileAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        build()
        if (s.disclaimerAccepted < Settings.DISCLAIMER_VERSION) showDisclaimer(firstRun = true)
    }

    private fun build() {
        val c = Ui.page(this, "Redmi Manager", back = false)

        // permessi mancanti
        permCard = Ui.card(c)

        // stato
        val st = Ui.card(c)
        Ui.section(st, "Stato dei backup").setPadding(0, 0, 0, 4)
        statusText = Ui.text(st, "")
        destText = Ui.note(st, "")

        // backup in corso
        runBox = Ui.card(c)
        runTitle = Ui.text(runBox, "", 17f)
        runDetail = Ui.note(runBox, "")
        runBar = Ui.progress(runBox)
        Ui.button(runBox, "Ferma", Ui.RED, outlined = true) { BackupWorker.stop(this) }

        backupBtn = Ui.button(c, "Fai il backup adesso", Ui.ORANGE) { startBackup() }
        Ui.button(c, "Ripristina un backup", Ui.BLUE) { startActivity(Intent(this, RestoreActivity::class.java)) }
        Ui.button(c, "Pulizia del telefono", Ui.GREEN) { startActivity(Intent(this, CleanActivity::class.java)) }
        Ui.button(c, "Backup di fabbrica (dati delle app)", Ui.BLUE, outlined = true) { factoryBackup() }
        Ui.button(c, "Impostazioni", Ui.ORANGE, outlined = true) { startActivity(Intent(this, SettingsActivity::class.java)) }
        Ui.button(c, "Registro attività", Ui.BLUE, outlined = true) { startActivity(Intent(this, LogActivity::class.java)) }
        Ui.button(c, "Avvertenze e informazioni", Ui.BLUE, outlined = true) { showDisclaimer(firstRun = false) }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        Ui.note(c, "Redmi Manager $ver – backup e ripristino senza Google, su NAS, chiavetta USB e PC di casa.")
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(ticker)
        if (s.disclaimerAccepted >= Settings.DISCLAIMER_VERSION) BackupWorker.schedule(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateRun()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateRun() {
        val running = Status.running || Status.starting
        (runBox.parent as android.view.View).visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        backupBtn.isEnabled = !running
        if (running) {
            runTitle.text = Status.title.ifEmpty { "Backup in corso…" }
            runDetail.text = Status.detail
            if (Status.percent >= 0) {
                runBar.isIndeterminate = false
                runBar.setProgressCompat(Status.percent, true)
            } else runBar.isIndeterminate = true
        } else if (runTitle.text.isNotEmpty()) {
            runTitle.text = ""
            refresh()
        }
    }

    private fun missingPerms(): List<String> =
        RUNTIME_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun refresh() {
        // permessi
        permCard.removeAllViews()
        val missing = missingPerms()
        val files = BackupEngine.hasFileAccess(this)
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        (permCard.parent as android.view.View).visibility =
            if (missing.isEmpty() && files && battery) android.view.View.GONE else android.view.View.VISIBLE
        if (missing.isNotEmpty() || !files) {
            Ui.text(permCard, "⚠ Mancano dei permessi", 17f).setTextColor(Ui.RED)
            Ui.note(permCard, "Servono per salvare rubrica, SMS, chiamate, calendario e i file (\"Accesso a tutti i file\").")
            Ui.button(permCard, "Concedi i permessi", Ui.RED) {
                if (missing.isNotEmpty()) askPerms.launch(missing.toTypedArray()) else askFileAccess()
            }
        }
        if (!battery) {
            Ui.note(permCard, "Per il backup automatico è meglio togliere Redmi Manager dal risparmio energetico.")
            Ui.button(permCard, "Togli dal risparmio energetico", Ui.BLUE, outlined = true) { askBattery() }
        }

        // stato
        val brand = Brands.get(s.brand)
        statusText.text = buildString {
            append("Telefono: ${s.deviceName}\nMarca: ${brand.name}\n")
            append(if (s.autoEnabled) "Backup automatico: ogni ${hoursText(s.autoHours)}" else "Backup automatico: spento")
            if (s.lastResult.isNotEmpty()) append("\n\nUltimo: ${s.lastResult}")
        }
        destText.text = buildString {
            if (!s.anyDestination()) append("Nessuna destinazione: apri Impostazioni e scegli NAS, chiavetta o PC.")
            if (s.nasEnabled) append("• NAS: ultimo backup ${Util.human(s.lastBackup("nas"))}\n")
            if (s.usbEnabled) append("• Chiavetta/cartella: ultimo backup ${Util.human(s.lastBackup("usb"))}\n")
            if (s.pcEnabled) append("• PC ${s.pcName}: ultimo backup ${Util.human(s.lastBackup("pc"))}\n")
        }.trimEnd()
        updateRun()
    }

    private fun hoursText(h: Int) = when {
        h % 168 == 0 -> if (h == 168) "settimana" else "${h / 168} settimane"
        h % 24 == 0 -> if (h == 24) "giorno (24 ore)" else "${h / 24} giorni"
        else -> "$h ore"
    }

    private fun askFileAccess() {
        if (Build.VERSION.SDK_INT < 30) return
        Ui.message(this, "Accesso a tutti i file",
            "Nella schermata che si apre attiva \"Consenti accesso per gestire tutti i file\" per Redmi Manager, poi torna indietro.") {
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun startBackup() {
        if (!s.anyDestination()) {
            Ui.confirm(this, "Dove salvo il backup?", "Non hai ancora scelto dove salvare i backup (NAS, chiavetta USB o PC). Apro le Impostazioni?", "Apri") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        if (missingPerms().isNotEmpty() || !BackupEngine.hasFileAccess(this)) {
            Ui.confirm(this, "Permessi mancanti", "Senza tutti i permessi alcuni dati non verranno salvati. Procedo lo stesso?", "Procedi") {
                go()
            }
        } else go()
    }

    private fun go() {
        Status.starting = true
        Status.set("Avvio del backup…")
        BackupWorker.startNow(this)
        updateRun()
        // se il worker non parte la schermata si rimette a posto da sola
        handler.postDelayed({ Status.starting = false }, 20000)
    }

    private fun factoryBackup() {
        val b = Brands.get(s.brand)
        val msg = b.howToBackup + (if (b.factoryPaths.isNotEmpty())
            "\n\nDopo averlo fatto, Redmi Manager copierà la cartella del backup di fabbrica al prossimo backup " +
                "(attiva \"Backup di fabbrica\" nelle Impostazioni › Cosa salvare)." else "")
        AlertDialog.Builder(this).setTitle("Backup di fabbrica – ${b.name}")
            .setMessage(msg)
            .setPositiveButton(if (b.backupPackages.isNotEmpty()) "Apri il backup del telefono" else "Apri Impostazioni") { _, _ ->
                if (!s.item("fabbrica") && b.factoryPaths.isNotEmpty()) s.setItem("fabbrica", true)
                Brands.openFactoryBackup(this, b)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showDisclaimer(firstRun: Boolean) {
        val text = resources.openRawResource(R.raw.avvertenze).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 12))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val b = AlertDialog.Builder(this).setTitle("Avvertenze").setView(sv)
        if (firstRun) {
            b.setCancelable(false)
                .setPositiveButton("Accetto") { _, _ ->
                    s.disclaimerAccepted = Settings.DISCLAIMER_VERSION
                    chooseBrand()
                }
                .setNegativeButton("Non accetto") { _, _ -> finish() }
        } else b.setPositiveButton("Chiudi", null)
        b.show()
    }

    /** Al primo avvio: scelta della marca (predefinita Xiaomi / Redmi / POCO) */
    private fun chooseBrand() {
        val names = Brands.ALL.map { it.name }.toTypedArray()
        val detected = Brands.detect()
        var sel = Brands.ALL.indexOfFirst { it.id == (detected?.id ?: Brands.DEFAULT) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Che telefono è?")
            .setCancelable(false)
            .setSingleChoiceItems(names, sel) { _, w -> sel = w }
            .setPositiveButton("Conferma") { _, _ ->
                s.brand = Brands.ALL[sel].id
                refresh()
                if (missingPerms().isNotEmpty()) askPerms.launch(missingPerms().toTypedArray())
                else if (!BackupEngine.hasFileAccess(this)) askFileAccess()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { BackupWorker.channel(this@MainActivity) }
                }
            }
            .show()
    }
}
