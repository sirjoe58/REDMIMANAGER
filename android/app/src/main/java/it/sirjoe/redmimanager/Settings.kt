package it.sirjoe.redmimanager

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray

/** Impostazioni dell'app. Le password sono salvate cifrate. */
class Settings(context: Context) {
    companion object {
        const val DISCLAIMER_VERSION = 1

        /** Cosa si puo' salvare: chiave -> descrizione */
        val ITEMS = linkedMapOf(
            "contatti" to "Rubrica (contatti)",
            "sms" to "SMS",
            "chiamate" to "Registro chiamate",
            "calendario" to "Calendario",
            "foto" to "Foto e video",
            "documenti" to "Documenti e download",
            "musica" to "Musica e registrazioni",
            "whatsapp" to "WhatsApp (foto, vocali, backup locale)",
            "app" to "App installate (file di installazione)",
            "fabbrica" to "Backup di fabbrica del telefono (dati delle app)"
        )
    }

    private val p: SharedPreferences =
        context.getSharedPreferences("config", Context.MODE_PRIVATE)

    private val secret: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "segreti",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("segreti_semplici", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = p.getString(k, d) ?: d
    private fun setStr(k: String, v: String) = p.edit { putString(k, v) }
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = p.edit { putBoolean(k, v) }
    private fun long(k: String) = p.getLong(k, 0L)
    private fun setLong(k: String, v: Long) = p.edit { putLong(k, v) }

    var disclaimerAccepted: Int
        get() = p.getInt("disclaimer", 0)
        set(v) = p.edit { putInt("disclaimer", v) }

    /** Marca del telefono (predefinita Xiaomi / Redmi / POCO) */
    var brand: String get() = str("brand", Brands.DEFAULT); set(v) = setStr("brand", v)

    /** Nome della cartella del telefono nei backup */
    var deviceName: String
        get() = str("device_name").ifBlank { Util.safeName("${Build.MANUFACTURER} ${Build.MODEL}") }
        set(v) = setStr("device_name", Util.safeName(v.trim()))

    fun item(key: String): Boolean = bool("item_$key", key != "fabbrica")
    fun setItem(key: String, on: Boolean) = setBool("item_$key", on)

    /** Altre cartelle del telefono da salvare (percorsi relativi alla memoria, es. "Telegram") */
    var extraFolders: List<String>
        get() {
            val arr = JSONArray(str("extra_folders", "[]"))
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(v) = setStr("extra_folders", JSONArray(v).toString())

    // ---------------------------------------------------------- NAS
    var nasEnabled: Boolean get() = bool("nas_on", false); set(v) = setBool("nas_on", v)
    var nasPath: String get() = str("nas_path"); set(v) = setStr("nas_path", Util.fritzWebToSmb(v))
    var nasUser: String get() = str("nas_user"); set(v) = setStr("nas_user", v.trim())
    var nasDomain: String get() = str("nas_domain"); set(v) = setStr("nas_domain", v.trim())
    var nasPassword: String
        get() = secret.getString("nas_password", "") ?: ""
        set(v) = secret.edit { putString("nas_password", v) }

    // ---------------------------------------------------------- chiavetta USB / cartella
    var usbEnabled: Boolean get() = bool("usb_on", false); set(v) = setBool("usb_on", v)
    var usbUri: String get() = str("usb_uri"); set(v) = setStr("usb_uri", v)
    var usbLabel: String get() = str("usb_label"); set(v) = setStr("usb_label", v)

    // ---------------------------------------------------------- PC di casa
    var pcEnabled: Boolean get() = bool("pc_on", false); set(v) = setBool("pc_on", v)
    var pcHost: String get() = str("pc_host"); set(v) = setStr("pc_host", v.trim())
    var pcPort: Int get() = p.getInt("pc_port", PcLink.PORT); set(v) = p.edit { putInt("pc_port", v) }
    var pcName: String get() = str("pc_name"); set(v) = setStr("pc_name", v)
    var pcId: String get() = str("pc_id"); set(v) = setStr("pc_id", v)
    var pcToken: String
        get() = secret.getString("pc_token", "") ?: ""
        set(v) = secret.edit { putString("pc_token", v) }

    // ---------------------------------------------------------- automatico
    var autoEnabled: Boolean get() = bool("auto_on", true); set(v) = setBool("auto_on", v)
    var autoHours: Int get() = p.getInt("auto_hours", 24); set(v) = p.edit { putInt("auto_hours", v) }
    var onlyCharging: Boolean get() = bool("only_charging", false); set(v) = setBool("only_charging", v)
    var keepCopies: Int get() = p.getInt("keep_copies", 10); set(v) = p.edit { putInt("keep_copies", v) }

    fun lastBackup(dest: String): Long = long("last_$dest")
    fun setLastBackup(dest: String, t: Long) = setLong("last_$dest", t)
    var lastResult: String get() = str("last_result"); set(v) = setStr("last_result", v)

    fun anyDestination() = nasEnabled || usbEnabled || pcEnabled
}
