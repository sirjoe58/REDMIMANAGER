package it.sirjoe.redmimanager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/** Un elemento di una cartella di destinazione */
data class Entry(val name: String, val isDir: Boolean, val size: Long)

/**
 * Una destinazione dei backup: NAS, chiavetta USB/cartella, PC di casa.
 * I percorsi sono relativi alla radice della destinazione e separati da "/".
 */
interface Dest : AutoCloseable {
    /** "nas", "usb" oppure "pc" */
    val key: String
    val label: String

    /** Controlla che sia raggiungibile; in caso contrario lancia un'eccezione con un messaggio chiaro */
    fun check()

    /** Dimensione del file, -1 se non esiste */
    fun size(path: String): Long

    fun write(path: String, input: InputStream, length: Long)
    fun writeBytes(path: String, data: ByteArray) = write(path, data.inputStream(), data.size.toLong())

    /** Contenuto della cartella (vuoto se non esiste) */
    fun list(path: String): List<Entry>
    fun open(path: String): InputStream
    fun readText(path: String): String = open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Cancella file o cartella (con tutto il contenuto) */
    fun delete(path: String)

    override fun close() {}

    companion object {
        /** Destinazioni attive nelle impostazioni (non ancora controllate) */
        fun enabled(ctx: Context, s: Settings): List<Dest> {
            val l = mutableListOf<Dest>()
            if (s.nasEnabled && s.nasPath.isNotBlank()) l += SmbDest(s)
            if (s.usbEnabled && s.usbUri.isNotBlank()) l += SafDest(ctx, Uri.parse(s.usbUri), s.usbLabel)
            if (s.pcEnabled && s.pcToken.isNotBlank()) l += PcDest(ctx, s)
            return l
        }

        fun byKey(ctx: Context, s: Settings, key: String): Dest? = when (key) {
            "nas" -> if (s.nasPath.isNotBlank()) SmbDest(s) else null
            "usb" -> if (s.usbUri.isNotBlank()) SafDest(ctx, Uri.parse(s.usbUri), s.usbLabel) else null
            "pc" -> if (s.pcToken.isNotBlank()) PcDest(ctx, s) else null
            else -> null
        }

        fun portOpen(host: String, port: Int, timeoutMs: Int = 1500): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (_: Exception) {
            false
        }
    }
}

// =================================================================== NAS (SMB)

/** Cartella condivisa del NAS (SMB, come le cartelle di rete di Windows). Stessa logica di MailAllegati. */
class SmbDest(private val s: Settings) : Dest {
    override val key = "nas"
    private val nas = Util.parseNas(s.nasPath)
    override val label: String get() = "NAS " + nas.display
    private val ctx: CIFSContext

    init {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            setProperty("jcifs.smb.client.connTimeout", "15000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
            // compatibile anche con NAS vecchi (SMB1, es. FRITZ!Box) e recenti (SMB3)
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        ctx = if (s.nasUser.isBlank()) {
            base.withGuestCrendentials()
        } else {
            base.withCredentials(NtlmPasswordAuthenticator(s.nasDomain.ifBlank { null }, s.nasUser, s.nasPassword))
        }
    }

    private fun url(path: String, dir: Boolean = false): String {
        val host = if (nas.port != 445) "${nas.server}:${nas.port}" else nas.server
        val segs = nas.segments + Util.cleanRel(path).split('/').filter { it.isNotEmpty() }
        return "smb://$host/" + segs.joinToString("/") + if (dir || segs.size <= 1) "/" else ""
    }

    override fun check() {
        if (!Dest.portOpen(nas.server, nas.port, 2500))
            throw IllegalStateException("Il NAS ${nas.server} non risponde (spento, o non sei sulla rete di casa)")
        try {
            SmbFile(url("", true), ctx).use { d ->
                if (!d.exists()) d.mkdirs()
                d.list()
            }
        } catch (e: Exception) {
            throw IllegalStateException(friendly(e))
        }
    }

    private fun friendly(e: Exception): String {
        val t = (e.message ?: e.javaClass.simpleName)
        val low = t.lowercase()
        return when {
            "logon" in low || "access" in low || "denied" in low || "password" in low ->
                "Utente o password del NAS non accettati, oppure l'utente non ha i permessi sulla cartella"
            "bad_network_name" in low || "network name" in low ->
                "La cartella condivisa indicata non esiste sul NAS: controlla il nome dopo l'indirizzo"
            else -> "NAS: $t"
        }
    }

    override fun size(path: String): Long = SmbFile(url(path), ctx).use { f ->
        if (f.exists() && !f.isDirectory) f.length() else -1L
    }

    override fun write(path: String, input: InputStream, length: Long) {
        val rel = Util.cleanRel(path)
        val parent = rel.substringBeforeLast('/', "")
        SmbFile(url(parent, true), ctx).use { d -> if (!d.exists()) d.mkdirs() }
        // prima un file temporaneo, poi lo si rinomina: niente file a meta' se il NAS cade
        SmbFile(url("$rel.parziale"), ctx).use { tmp ->
            tmp.openOutputStream().use { out -> input.copyTo(out, 256 * 1024) }
            SmbFile(url(rel), ctx).use { dest ->
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
            }
        }
    }

    override fun list(path: String): List<Entry> = SmbFile(url(path, true), ctx).use { d ->
        if (!d.exists()) return emptyList()
        d.listFiles().map { f ->
            f.use {
                val dir = it.isDirectory
                Entry(it.name.trimEnd('/'), dir, if (dir) 0L else it.length())
            }
        }
    }

    override fun open(path: String): InputStream {
        val f = SmbFile(url(path), ctx)
        if (!f.exists()) throw FileNotFoundException(path)
        return f.openInputStream()
    }

    override fun delete(path: String) {
        val rel = Util.cleanRel(path)
        if (rel.isEmpty()) return
        SmbFile(url(rel), ctx).use { f ->
            if (!f.exists()) return
            if (f.isDirectory) SmbFile(url(rel, true), ctx).use { it.delete() } else f.delete()
        }
    }

    override fun close() {
        try {
            ctx.close()
        } catch (_: Exception) {
        }
    }
}

// =================================================================== chiavetta USB / cartella (SAF)

/** Cartella scelta dall'utente con il selettore di Android (chiavetta USB OTG, scheda SD, memoria) */
class SafDest(private val ctx: Context, private val tree: Uri, private val name: String) : Dest {
    override val key = "usb"
    override val label: String get() = "Chiavetta/cartella " + name.ifBlank { "scelta" }
    private val cr = ctx.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    private data class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    /** cache: id cartella -> figli */
    private val children = HashMap<String, MutableMap<String, Doc>>()
    private val dirIds = HashMap<String, String>().apply { put("", rootId) }

    private fun kids(dirId: String): MutableMap<String, Doc> = children.getOrPut(dirId) {
        val m = LinkedHashMap<String, Doc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        cr.query(
            uri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                m[n] = Doc(c.getString(0), n, dir, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        m
    }

    /** id della cartella (la crea se create=true), null se non esiste */
    private fun dirId(path: String, create: Boolean): String? {
        val rel = Util.cleanRel(path)
        dirIds[rel]?.let { return it }
        var cur = rootId
        var acc = ""
        for (seg in rel.split('/').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val known = dirIds[acc]
            if (known != null) {
                cur = known; continue
            }
            val k = kids(cur)
            val d = k[seg]
            cur = if (d != null && d.isDir) d.id else {
                if (!create) return null
                val u = DocumentsContract.createDocument(
                    cr, DocumentsContract.buildDocumentUriUsingTree(tree, cur),
                    DocumentsContract.Document.MIME_TYPE_DIR, seg
                ) ?: throw IllegalStateException("Impossibile creare la cartella $acc")
                val id = DocumentsContract.getDocumentId(u)
                k[seg] = Doc(id, seg, true, 0)
                id
            }
            dirIds[acc] = cur
        }
        return cur
    }

    private fun find(path: String): Doc? {
        val rel = Util.cleanRel(path)
        val parent = dirId(rel.substringBeforeLast('/', ""), false) ?: return null
        return kids(parent)[rel.substringAfterLast('/')]
    }

    override fun check() {
        val ok = try {
            cr.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } && run {
                children.clear(); dirIds.clear(); dirIds[""] = rootId
                kids(rootId); true
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) throw IllegalStateException("Chiavetta/cartella non collegata o non più accessibile")
    }

    override fun size(path: String): Long = find(path)?.takeIf { !it.isDir }?.size ?: -1L

    override fun write(path: String, input: InputStream, length: Long) {
        val rel = Util.cleanRel(path)
        val fileName = rel.substringAfterLast('/')
        val parent = dirId(rel.substringBeforeLast('/', ""), true)!!
        val k = kids(parent)
        k[fileName]?.let { old ->
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, old.id))
            k.remove(fileName)
        }
        k["$fileName.parziale"]?.let { old ->
            try {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, old.id))
            } catch (_: Exception) {
            }
            k.remove("$fileName.parziale")
        }
        var u = DocumentsContract.createDocument(
            cr, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
            "application/octet-stream", "$fileName.parziale"
        ) ?: throw IllegalStateException("Impossibile creare $rel")
        cr.openOutputStream(u, "wt")!!.use { out -> input.copyTo(out, 256 * 1024) }
        u = DocumentsContract.renameDocument(cr, u, fileName) ?: u
        k[fileName] = Doc(DocumentsContract.getDocumentId(u), fileName, false, length)
    }

    override fun list(path: String): List<Entry> {
        val id = dirId(path, false) ?: return emptyList()
        children.remove(id)
        return kids(id).values.map { Entry(it.name, it.isDir, it.size) }
    }

    override fun open(path: String): InputStream {
        val d = find(path) ?: throw FileNotFoundException(path)
        return cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))
            ?: throw FileNotFoundException(path)
    }

    override fun delete(path: String) {
        val rel = Util.cleanRel(path)
        if (rel.isEmpty()) return
        val d = find(rel) ?: return
        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, d.id))
        val parent = dirIds[rel.substringBeforeLast('/', "")]
        if (parent != null) children[parent]?.remove(d.name)
        dirIds.keys.removeAll { it == rel || it.startsWith("$rel/") }
    }
}

// =================================================================== PC di casa (programma Redmi Manager)

/** Il programma Redmi Manager aperto su un PC Windows o Mac della rete di casa */
class PcDest(private val ctx: Context, private val s: Settings) : Dest {
    override val key = "pc"
    override val label: String get() = "PC " + s.pcName.ifBlank { s.pcHost }

    private fun enc(p: String) = URLEncoder.encode(Util.cleanRel(p), "UTF-8")

    private fun conn(method: String, endpoint: String, timeout: Int = 15000): HttpURLConnection {
        val c = URL("http://${s.pcHost}:${s.pcPort}$endpoint").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = timeout
        c.setRequestProperty("X-Token", s.pcToken)
        c.setRequestProperty("X-Device", s.deviceName)
        return c
    }

    private fun HttpURLConnection.ok(): HttpURLConnection {
        val code = responseCode
        if (code == 401) throw IllegalStateException("Il PC non riconosce questo telefono: collegalo di nuovo con il codice QR")
        if (code == 404) throw FileNotFoundException(url.toString())
        if (code !in 200..299) {
            val msg = try { errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: "" } catch (_: Exception) { "" }
            throw IllegalStateException("PC: errore $code $msg".trim())
        }
        return this
    }

    override fun check() {
        // il PC potrebbe aver cambiato indirizzo: lo si cerca di nuovo in rete
        if (!Dest.portOpen(s.pcHost, s.pcPort, 1500)) {
            val found = PcLink.discover(ctx, 2500).firstOrNull { it.id == s.pcId && s.pcId.isNotEmpty() }
                ?: throw IllegalStateException("Il PC ${s.pcName.ifBlank { s.pcHost }} non è acceso o il programma è chiuso")
            s.pcHost = found.host
            s.pcPort = found.port
        }
        conn("GET", "/api/info").ok().inputStream.use { it.readBytes() }
        PcLink.sendHello(ctx, s)
    }

    override fun size(path: String): Long {
        val c = conn("GET", "/api/size?path=${enc(path)}").ok()
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).trim().toLongOrNull() ?: -1L }
    }

    override fun write(path: String, input: InputStream, length: Long) {
        val c = conn("PUT", "/api/file?path=${enc(path)}", 120000)
        c.doOutput = true
        if (length >= 0) c.setFixedLengthStreamingMode(length) else c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { out -> input.copyTo(out, 256 * 1024) }
        c.ok().inputStream.use { it.readBytes() }
    }

    override fun list(path: String): List<Entry> {
        val c = conn("GET", "/api/list?path=${enc(path)}")
        if (c.responseCode == 404) return emptyList()
        val arr = org.json.JSONArray(c.ok().inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("name"), o.optBoolean("dir"), o.optLong("size"))
        }
    }

    override fun open(path: String): InputStream = conn("GET", "/api/file?path=${enc(path)}", 120000).ok().inputStream

    override fun delete(path: String) {
        conn("DELETE", "/api/file?path=${enc(path)}").ok().inputStream.use { it.readBytes() }
    }
}
