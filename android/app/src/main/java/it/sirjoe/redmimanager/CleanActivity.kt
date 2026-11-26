package it.sirjoe.redmimanager

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pulizia del telefono: miniature, apk, doppioni, cartelle vuote, download vecchi, file grandi */
class CleanActivity : AppCompatActivity() {
    private lateinit var area: LinearLayout
    @Volatile private var stop = false

    /** per ogni categoria: quali file sono selezionati */
    private val selected = HashMap<String, BooleanArray>()
    private var cats: List<Cleaner.Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Ui.page(this, "Pulizia del telefono")
        area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(area)
        intro()
    }

    override fun onDestroy() {
        super.onDestroy()
        stop = true
    }

    private fun intro() {
        area.removeAllViews()
        Ui.text(area, "Cerco nella memoria del telefono i file che occupano spazio inutilmente. " +
            "Niente viene cancellato senza la tua conferma.")
        if (!BackupEngine.hasFileAccess(this)) {
            Ui.text(area, "⚠ Serve il permesso \"Accesso a tutti i file\": concedilo dalla schermata principale.").setTextColor(Ui.RED)
            return
        }
        Ui.button(area, "Analizza il telefono", Ui.GREEN) { scan() }
    }

    private fun scan() {
        area.removeAllViews()
        val t = Ui.text(area, "Analisi in corso…", 17f)
        Ui.progress(area)
        stop = false
        lifecycleScope.launch {
            cats = withContext(Dispatchers.IO) { Cleaner.scan({ m -> runOnUiThread { t.text = m } }, { stop }) }
            selected.clear()
            for (k in cats) selected[k.key] = BooleanArray(k.items.size) { k.preselect }
            show()
        }
    }

    private fun show() {
        area.removeAllViews()
        if (cats.isEmpty()) {
            Ui.text(area, "Il telefono è già pulito ✓", 18f)
            return
        }
        Ui.text(area, "Spunta quello che vuoi cancellare. Tocca \"Vedi i file\" per scegliere uno per uno.")
        for (k in cats) {
            val card = Ui.card(area)
            val sel = selected[k.key]!!
            val chosen = k.items.filterIndexed { i, _ -> sel[i] }
            val cb: MaterialCheckBox = Ui.check(card, "${k.title}\n${k.items.size} elementi – ${Util.size(k.bytes)}", chosen.isNotEmpty()) { on ->
                sel.fill(on); show()
            }
            cb.textSize = 17f
            Ui.note(card, k.hint)
            if (chosen.isNotEmpty() && chosen.size < k.items.size)
                Ui.note(card, "Selezionati: ${chosen.size} (${Util.size(chosen.sumOf { it.file.length() })})")
            Ui.button(card, "Vedi i file", Ui.BLUE, outlined = true) { pick(k) }
        }
        val all = cats.flatMap { k -> k.items.filterIndexed { i, _ -> selected[k.key]!![i] } }
        Ui.button(area, "Cancella i selezionati (${all.size} – ${Util.size(all.sumOf { it.file.length() })})", Ui.RED) {
            if (all.isEmpty()) return@button
            Ui.confirm(this, "Cancellare?", "Verranno cancellati ${all.size} elementi (${Util.size(all.sumOf { it.file.length() })}). " +
                "I file cancellati non si possono recuperare.", "Cancella") { delete(all) }
        }
        Ui.button(area, "Analizza di nuovo", Ui.GREEN, outlined = true) { scan() }
    }

    private fun pick(k: Cleaner.Category) {
        val sel = selected[k.key]!!
        val max = minOf(k.items.size, 1000)
        val root = Util.storageRoot.path + "/"
        val labels = k.items.take(max).map { item ->
            val p = item.file.path.removePrefix(root)
            p + "  (" + Util.size(item.file.length()) + ")" + if (item.note.isNotEmpty()) "\n  " + item.note else ""
        }.toTypedArray()
        val view = sel.copyOf(max)
        AlertDialog.Builder(this).setTitle(k.title + if (k.items.size > max) " (primi $max)" else "")
            .setMultiChoiceItems(labels, view) { _, w, c -> view[w] = c }
            .setPositiveButton("OK") { _, _ -> for (i in 0 until max) sel[i] = view[i]; show() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun delete(items: List<Cleaner.Item>) {
        area.removeAllViews()
        Ui.text(area, "Cancello…", 17f)
        Ui.progress(area)
        lifecycleScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { Cleaner.delete(items) }
            AppLog(this@CleanActivity).add("Pulizia: cancellati $n elementi, liberati ${Util.size(bytes)}")
            area.removeAllViews()
            Ui.text(area, "Fatto ✓\nCancellati $n elementi, liberati ${Util.size(bytes)}.", 18f)
            Ui.button(area, "Analizza di nuovo", Ui.GREEN) { scan() }
        }
    }
}
