package it.sirjoe.redmimanager

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {
    /** Memoria principale del telefono (/storage/emulated/0) */
    val storageRoot: File get() = Environment.getExternalStorageDirectory()

    fun safeName(name: String): String {
        var n = name.replace(Regex("[\\r\\n]"), "")
            .replace(Regex("[<>:\"/\\\\|?*#%\\x00-\\x1f]"), "_")
            .trim(' ', '.')
        if (n.isEmpty()) n = "telefono"
        if (n.length > 120) n = n.take(120)
        return n
    }

    /** Percorso relativo sicuro (niente "..", niente percorsi assoluti), separato da "/" */
    fun cleanRel(path: String): String =
        path.replace('\\', '/').split('/').map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    fun join(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString("/") { it.trim('/') }.trim('/')

    fun stamp(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ITALY).format(Date(t))

    fun human(t: Long): String =
        if (t <= 0) "mai" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(t))

    /** "2026-11-26_22-15" -> "26/11/2026 22:15" */
    fun stampToHuman(s: String): String {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})").find(s) ?: return s
        val (y, mo, d, h, mi) = m.destructured
        return "$d/$mo/$y $h:$mi"
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(Locale.ITALY, kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(Locale.ITALY, mb)
        return "%.2f GB".format(Locale.ITALY, mb / 1024.0)
    }

    fun shortError(e: Throwable): String {
        val t = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return if (t.length < 140) t else t.take(137) + "..."
    }

    data class NasPath(val server: String, val port: Int, val segments: List<String>) {
        val display get() = "\\\\" + server + (if (port != 445) ":$port" else "") + "\\" + segments.joinToString("\\")
    }

    /** Accetta \\server\condivisa\sotto, //server/condivisa o smb://server/condivisa */
    /** http://192.168.1.198/nas#/files/Disco/Cartella (pagina del FRITZ!Box) -> \\192.168.1.198\FRITZ.NAS\Disco\Cartella */
    fun fritzWebToSmb(path: String): String {
        var p = path.trim()
        if (Regex("^(\\\\\\\\|//)[^/\\\\]+[/\\\\]nas#", RegexOption.IGNORE_CASE).containsMatchIn(p))
            p = "http://" + p.trimStart('\\', '/').replace('\\', '/')   // //192.168.1.198/nas#/files/... senza http:
        if (!p.lowercase().startsWith("http://") && !p.lowercase().startsWith("https://")) return p
        val u = android.net.Uri.parse(p)
        val host = u.host ?: return p
        val frag = u.fragment ?: ""
        val sub = Regex("files/?(.*)$").find(frag)?.groupValues?.get(1)
            ?: Regex("/nas/?(.*)$").find(u.path ?: "")?.groupValues?.get(1) ?: ""
        val parts = java.net.URLDecoder.decode(sub.replace("+", "%2B"), "UTF-8").split('/').filter { it.isNotBlank() }
        return "\\\\" + host + "\\FRITZ.NAS" + parts.joinToString("") { "\\" + it }
    }

    fun parseNas(path: String): NasPath {
        var p = fritzWebToSmb(path)
        if (p.lowercase().startsWith("smb://")) p = p.substring(6)
        p = p.replace('\\', '/').trim('/')
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) throw IllegalArgumentException(
            "Percorso NAS non valido. Esempio: \\\\192.168.1.198\\FRITZ.NAS")
        var server = parts[0]
        var port = 445
        if (server.contains(':')) {
            port = server.substringAfterLast(':').toIntOrNull() ?: 445
            server = server.substringBeforeLast(':')
        }
        return NasPath(server, port, parts.drop(1))
    }
}

/** Registro attivita' (file di testo nella memoria privata dell'app) */
class AppLog(context: Context) {
    private val file = File(context.filesDir, "registro.txt")
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY)

    @Synchronized
    fun add(text: String) {
        try {
            if (file.exists() && file.length() > 400_000) {
                val keep = file.readLines().takeLast(800)
                file.writeText(keep.joinToString("\n") + "\n")
            }
            file.appendText("${fmt.format(Date())}  $text\n")
        } catch (_: Exception) {
        }
    }

    fun tail(lines: Int = 300): String = try {
        if (file.exists()) file.readLines().takeLast(lines).joinToString("\n") else ""
    } catch (_: Exception) {
        ""
    }

    fun clear() {
        file.delete()
    }
}
