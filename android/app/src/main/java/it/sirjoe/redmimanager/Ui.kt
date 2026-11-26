package it.sirjoe.redmimanager

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Piccolo aiuto per costruire le schermate (testi grandi e pulsanti ben visibili) */
object Ui {
    const val ORANGE = 0xFFE8590C.toInt()
    const val BLUE = 0xFF1F6F8B.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val RED = 0xFFC62828.toInt()

    fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Crea la pagina: intestazione arancione + contenuto scorrevole. Restituisce il contenitore. */
    fun page(a: Activity, title: String, back: Boolean = true): LinearLayout {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ORANGE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 12), dp(a, 14), dp(a, 12), dp(a, 14))
        }
        if (back) header.addView(TextView(a).apply {
            text = "←"
            setTextColor(Color.WHITE)
            textSize = 26f
            setPadding(dp(a, 4), 0, dp(a, 16), 0)
            setOnClickListener { a.finish() }
        })
        header.addView(TextView(a).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(header)
        val scroll = ScrollView(a).apply { isFillViewport = true }
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 32))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        a.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        return content
    }

    fun section(parent: LinearLayout, text: String): TextView = TextView(parent.context).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BLUE)
        setPadding(0, dp(context, 22), 0, dp(context, 4))
        parent.addView(this)
    }

    fun text(parent: LinearLayout, text: String, size: Float = 16f): TextView = TextView(parent.context).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(context, 4), 0, dp(context, 4))
        parent.addView(this)
    }

    fun note(parent: LinearLayout, text: String): TextView = text(parent, text, 14f).apply { alpha = 0.75f }

    fun button(parent: LinearLayout, text: String, color: Int = ORANGE, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(parent.context)
        b.text = text
        b.textSize = 17f
        b.isAllCaps = false
        if (!outlined) b.setBackgroundColor(color) else b.setTextColor(color)
        b.minHeight = dp(parent.context, 56)
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(parent.context, 6)
        })
        return b
    }

    fun check(parent: LinearLayout, text: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): MaterialCheckBox =
        MaterialCheckBox(parent.context).apply {
            this.text = text
            textSize = 16f
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
            parent.addView(this)
        }

    fun switch(parent: LinearLayout, text: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): MaterialSwitch =
        MaterialSwitch(parent.context).apply {
            this.text = text
            textSize = 17f
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    fun edit(parent: LinearLayout, hint: String, value: String, password: Boolean = false, multiline: Boolean = false): TextInputEditText {
        val til = TextInputLayout(parent.context, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        if (password) til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val e = TextInputEditText(til.context)
        e.setText(value)
        e.textSize = 16f
        e.inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (multiline) e.minLines = 2
        til.addView(e)
        parent.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(parent.context, 6)
        })
        return e
    }

    fun spinner(parent: LinearLayout, items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val sp = Spinner(parent.context)
        sp.adapter = ArrayAdapter(parent.context, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, maxOf(0, items.size - 1)))
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        sp.minimumHeight = dp(parent.context, 52)
        parent.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sp
    }

    /** Riquadro con bordo arrotondato; restituisce il contenitore interno */
    fun card(parent: LinearLayout): LinearLayout {
        val c = MaterialCardView(parent.context)
        c.radius = dp(parent.context, 14).toFloat()
        c.cardElevation = dp(parent.context, 2).toFloat()
        val inner = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 14))
        }
        c.addView(inner)
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(parent.context, 12)
        })
        return inner
    }

    fun progress(parent: LinearLayout): LinearProgressIndicator = LinearProgressIndicator(parent.context).apply {
        isIndeterminate = true
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(parent.context, 8)
        })
    }

    fun message(a: Activity, title: String, msg: String, ok: (() -> Unit)? = null) {
        if (a.isFinishing) return
        AlertDialog.Builder(a).setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok?.invoke() }
            .show()
    }

    fun confirm(a: Activity, title: String, msg: String, yes: String = "Sì", onYes: () -> Unit) {
        if (a.isFinishing) return
        AlertDialog.Builder(a).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
