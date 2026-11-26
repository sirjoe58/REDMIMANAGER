package it.sirjoe.redmimanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var s: Settings
    private lateinit var deviceName: TextInputEditText
    private lateinit var extra: TextInputEditText
    private lateinit var nasSwitch: MaterialSwitch
    private lateinit var nasPath: TextInputEditText
    private lateinit var nasUser: TextInputEditText
    private lateinit var nasPass: TextInputEditText
    private lateinit var nasDomain: TextInputEditText
    private lateinit var usbSwitch: MaterialSwitch
    private lateinit var usbText: TextView
    private lateinit var pcSwitch: MaterialSwitch
    private lateinit var pcText: TextView

    private val HOURS = listOf(6, 12, 24, 48, 168)
    private val HOURS_TXT = listOf("ogni 6 ore", "ogni 12 ore", "ogni giorno", "ogni 2 giorni", "ogni settimana")
    private val KEEP = listOf(3, 5, 10, 20, 50)

    private val pickUsb = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
        }
        s.usbUri = uri.toString()
        s.usbLabel = labelOf(uri)
        s.usbEnabled = true
        usbSwitch.isChecked = true
        updateUsb()
    }

    private val scanQr = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try { PcLink.pairFromQr(this@SettingsActivity, s, text); null } catch (e: Exception) { Util.shortError(e) }
            }
            if (err == null) {
                pcSwitch.isChecked = true
                updatePc()
                Ui.message(this@SettingsActivity, "PC collegato", "Telefono collegato a ${s.pcName}. I backup verranno inviati anche al PC.")
            } else Ui.message(this@SettingsActivity, "Collegamento non riuscito", err)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        val c = Ui.page(this, "Impostazioni")

        // ------------------------------------------------ telefono
        Ui.section(c, "Telefono")
        Ui.note(c, "Marca del telefono (serve per il backup di fabbrica):")
        val brands = Brands.ALL
        Ui.spinner(c, brands.map { it.name }, brands.indexOfFirst { it.id == s.brand }.coerceAtLeast(0)) { s.brand = brands[it].id }
        deviceName = Ui.edit(c, "Nome del telefono nei backup", s.deviceName)
        Ui.note(c, "Ogni telefono ha la sua cartella. Se cambi telefono e vuoi continuare lo stesso backup, usa lo stesso nome.")

        // ------------------------------------------------ cosa salvare
        Ui.section(c, "Cosa salvare")
        for ((k, label) in Settings.ITEMS) Ui.check(c, label, s.item(k)) { s.setItem(k, it) }
        extra = Ui.edit(c, "Altre cartelle del telefono (una per riga, es. Telegram)", s.extraFolders.joinToString("\n"), multiline = true)

        // ------------------------------------------------ NAS
        Ui.section(c, "NAS di casa")
        nasSwitch = Ui.switch(c, "Salva sul NAS", s.nasEnabled) { s.nasEnabled = it }
        Ui.note(c, "Il telefono si collega da solo al NAS appena lo vede acceso sulla rete di casa.")
        nasPath = Ui.edit(c, "Percorso, es. \\\\192.168.1.198\\FRITZ.NAS\\Lavori_Condominio", s.nasPath)
        nasUser = Ui.edit(c, "Utente del NAS (vuoto se non serve)", s.nasUser)
        nasPass = Ui.edit(c, "Password del NAS", s.nasPassword, password = true)
        nasDomain = Ui.edit(c, "Dominio (di solito vuoto)", s.nasDomain)
        Ui.button(c, "Cerca il NAS in rete", Ui.BLUE, outlined = true) { findNas() }
        Ui.button(c, "Prova la connessione al NAS", Ui.BLUE) { testNas() }

        // ------------------------------------------------ chiavetta
        Ui.section(c, "Chiavetta USB o cartella")
        usbSwitch = Ui.switch(c, "Salva sulla chiavetta/cartella", s.usbEnabled) { s.usbEnabled = it }
        usbText = Ui.note(c, "")
        Ui.button(c, "Scegli la chiavetta o la cartella", Ui.BLUE, outlined = true) {
            Ui.message(this, "Chiavetta USB",
                "Collega la chiavetta al telefono (con l'adattatore OTG se serve). Nella schermata che si apre " +
                    "scegli la chiavetta dal menu ☰ e premi \"Usa questa cartella\" › \"Consenti\".\n\n" +
                    "Il backup partirà da solo ogni volta che la chiavetta è collegata.") {
                pickUsb.launch(null)
            }
        }
        updateUsb()

        // ------------------------------------------------ PC
        Ui.section(c, "PC di casa (Windows o Mac)")
        pcSwitch = Ui.switch(c, "Invia i backup al PC", s.pcEnabled) { s.pcEnabled = it && s.pcToken.isNotEmpty() }
        pcText = Ui.note(c, "")
        Ui.button(c, "Collega con il codice QR", Ui.BLUE) {
            scanQr.launch(ScanOptions().setPrompt("Inquadra il codice QR mostrato da Redmi Manager sul PC")
                .setBeepEnabled(false).setOrientationLocked(false))
        }
        Ui.button(c, "Cerca il PC e inserisci il codice", Ui.BLUE, outlined = true) { findPc() }
        updatePc()

        // ------------------------------------------------ automatico
        Ui.section(c, "Backup automatico")
        Ui.switch(c, "Backup automatico", s.autoEnabled) { s.autoEnabled = it }
        Ui.note(c, "Ogni quanto:")
        Ui.spinner(c, HOURS_TXT, HOURS.indexOf(s.autoHours).let { if (it < 0) 2 else it }) { s.autoHours = HOURS[it] }
        Ui.check(c, "Solo quando il telefono è in carica", s.onlyCharging) { s.onlyCharging = it }
        Ui.note(c, "Quante copie datate di rubrica, SMS, chiamate e calendario conservare:")
        Ui.spinner(c, KEEP.map { "ultime $it" }, KEEP.indexOf(s.keepCopies).let { if (it < 0) 2 else it }) { s.keepCopies = KEEP[it] }
        Ui.note(c, "Foto, video e file vanno in un archivio unico: si copiano solo quelli nuovi e non si cancella mai niente.")
    }

    override fun onPause() {
        super.onPause()
        save()
        BackupWorker.schedule(this)
    }

    private fun save() {
        s.deviceName = deviceName.text.toString()
        s.extraFolders = extra.text.toString().split('\n').map { Util.cleanRel(it) }.filter { it.isNotEmpty() }
        s.nasPath = nasPath.text.toString()
        s.nasUser = nasUser.text.toString()
        s.nasPassword = nasPass.text.toString()
        s.nasDomain = nasDomain.text.toString()
    }

    private fun labelOf(uri: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(uri)  // es. "1A2B-3C4D:Backup"
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        (if (vol == "primary") "Memoria del telefono" else "Chiavetta/scheda $vol") + if (path.isNotEmpty()) " › $path" else ""
    } catch (_: Exception) {
        uri.toString()
    }

    private fun updateUsb() {
        usbText.text = if (s.usbUri.isEmpty()) "Nessuna chiavetta o cartella scelta." else "Scelta: ${s.usbLabel}"
    }

    private fun updatePc() {
        pcText.text = if (s.pcToken.isEmpty()) "Nessun PC collegato. Apri Redmi Manager sul PC e usa il codice QR."
        else "Collegato a: ${s.pcName} (${s.pcHost})"
    }

    // ------------------------------------------------------------------ NAS

    private fun testNas() {
        save()
        nasPath.setText(s.nasPath)
        if (s.nasPath.isBlank()) { Ui.message(this, "NAS", "Scrivi prima il percorso del NAS o premi \"Cerca il NAS in rete\"."); return }
        val wait = AlertDialog.Builder(this).setMessage("Provo a collegarmi al NAS…").setCancelable(false).show()
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try { SmbDest(s).use { it.check(); it.list("") }; null } catch (e: Exception) { Util.shortError(e) }
            }
            wait.dismiss()
            if (err == null) {
                s.nasEnabled = true
                nasSwitch.isChecked = true
                Ui.message(this@SettingsActivity, "NAS collegato ✓", "Il NAS risponde. I backup andranno in ${s.nasPath}\\${BackupEngine.ROOT}.")
            } else Ui.message(this@SettingsActivity, "NAS non raggiungibile", err)
        }
    }

    private fun findNas() {
        save()
        val wait = AlertDialog.Builder(this).setMessage("Cerco i NAS nella rete di casa… (circa 20 secondi)").setCancelable(false).show()
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { try { NasFinder.scan() } catch (_: Exception) { emptyList() } }
            wait.dismiss()
            if (found.isEmpty()) {
                Ui.message(this@SettingsActivity, "Nessun NAS trovato",
                    "Controlla che il telefono sia collegato al Wi-Fi di casa e che il NAS sia acceso con la condivisione file (SMB) attiva.")
                return@launch
            }
            val labels = found.map { if (it.name.isNotEmpty()) "${it.host}  (${it.name})" else it.host }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity).setTitle("NAS trovati")
                .setItems(labels) { _, i -> chooseShare(found[i].host) }
                .setNegativeButton("Annulla", null).show()
        }
    }

    private fun chooseShare(host: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(NasFinder.shares(host, s.nasUser, s.nasPassword, s.nasDomain)) } catch (e: Exception) { Result.failure(e) }
            }
            val shares = r.getOrNull()
            if (shares.isNullOrEmpty()) {
                nasPath.setText("\\\\$host\\")
                Ui.message(this@SettingsActivity, "Cartella condivisa",
                    "Ho trovato il NAS $host ma non riesco a leggere le cartelle condivise" +
                        (r.exceptionOrNull()?.let { " (${Util.shortError(it)})" } ?: "") +
                        ". Inserisci utente e password e riprova, oppure completa il percorso a mano (per il FRITZ!Box: \\\\$host\\FRITZ.NAS).")
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity).setTitle("Cartelle condivise di $host")
                .setItems(shares.toTypedArray()) { _, i ->
                    nasPath.setText("\\\\$host\\${shares[i]}")
                    Ui.message(this@SettingsActivity, "Percorso impostato",
                        "Percorso: \\\\$host\\${shares[i]}\nSe vuoi una sottocartella (es. un disco USB del FRITZ!Box) aggiungila in fondo, poi premi \"Prova la connessione\".")
                }
                .setNegativeButton("Annulla", null).show()
        }
    }

    // ------------------------------------------------------------------ PC

    private fun findPc() {
        val wait = AlertDialog.Builder(this).setMessage("Cerco i PC con Redmi Manager aperto…").setCancelable(false).show()
        lifecycleScope.launch {
            val pcs = withContext(Dispatchers.IO) { PcLink.discover(this@SettingsActivity, 3500) }
            wait.dismiss()
            if (pcs.isEmpty()) {
                Ui.message(this@SettingsActivity, "Nessun PC trovato",
                    "Apri Redmi Manager sul PC (stessa rete di casa del telefono). Se il PC chiede di consentire l'accesso alla rete, premi Consenti.")
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity).setTitle("PC trovati")
                .setItems(pcs.map { "${it.name}  (${it.host})" }.toTypedArray()) { _, i -> askCode(pcs[i]) }
                .setNegativeButton("Annulla", null).show()
        }
    }

    private fun askCode(pc: PcLink.Pc) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
        }
        val code = Ui.edit(box, "Codice a 6 cifre mostrato sul PC", "")
        code.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        AlertDialog.Builder(this).setTitle("Collega a ${pc.name}").setView(box)
            .setPositiveButton("Collega") { _, _ ->
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        try { PcLink.pair(this@SettingsActivity, s, pc.host, pc.port, code.text.toString()); null } catch (e: Exception) { Util.shortError(e) }
                    }
                    if (err == null) {
                        pcSwitch.isChecked = true
                        updatePc()
                        Ui.message(this@SettingsActivity, "PC collegato ✓", "Telefono collegato a ${s.pcName}.")
                    } else Ui.message(this@SettingsActivity, "Collegamento non riuscito", err)
                }
            }
            .setNegativeButton("Annulla", null).show()
    }
}
