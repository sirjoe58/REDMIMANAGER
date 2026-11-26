package it.sirjoe.redmimanager

import android.graphics.Typeface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Ui.page(this, "Registro attività")
        val log = AppLog(this)
        Ui.button(c, "Cancella il registro", Ui.RED, outlined = true) {
            log.clear(); recreate()
        }
        val t = Ui.text(c, log.tail().ifEmpty { "Il registro è vuoto." }, 13f)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
    }
}
