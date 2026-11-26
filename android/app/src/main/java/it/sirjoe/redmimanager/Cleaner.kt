package it.sirjoe.redmimanager

import java.io.File
import java.security.MessageDigest

/** Pulizia della memoria del telefono: trova file inutili o doppi. Cancella solo quello che sceglie l'utente. */
object Cleaner {
    data class Item(val file: File, val note: String = "")
    data class Category(val key: String, val title: String, val hint: String, val items: List<Item>, val preselect: Boolean) {
        val bytes get() = items.sumOf { it.file.length() }
    }

    private const val BIG = 100L * 1024 * 1024
    private const val OLD_DOWNLOAD_DAYS = 90

    fun scan(progress: (String) -> Unit, stop: () -> Boolean): List<Category> {
        val root = Util.storageRoot
        val thumbs = mutableListOf<Item>()
        val apks = mutableListOf<Item>()
        val oldDl = mutableListOf<Item>()
        val big = mutableListOf<Item>()
        val empty = mutableListOf<Item>()
        val logs = mutableListOf<Item>()
        val bySize = HashMap<Long, MutableList<File>>()
        val download = File(root, "Download").absolutePath
        val now = System.currentTimeMillis()
        var count = 0

        fun walk(f: File, inThumbs: Boolean) {
            if (stop()) return
            val name = f.name
            if (f.isDirectory) {
                if (f.absolutePath.startsWith(File(root, "Android/data").absolutePath) ||
                    f.absolutePath.startsWith(File(root, "Android/obb").absolutePath)) return
                val kids = f.listFiles() ?: return
                if (kids.isEmpty() && f != root && !f.absolutePath.startsWith(File(root, "Android").absolutePath)) {
                    empty += Item(f)
                    return
                }
                val t = inThumbs || name.equals(".thumbnails", true)
                kids.forEach { walk(it, t) }
                return
            }
            if (!f.isFile) return
            if (++count % 500 == 0) progress("Analizzati $count file…")
            val len = f.length()
            when {
                inThumbs -> thumbs += Item(f)
                name.endsWith(".apk", true) || name.endsWith(".apks", true) || name.endsWith(".xapk", true) ->
                    apks += Item(f, "file di installazione")
                name.endsWith(".log", true) || name.endsWith(".tmp", true) -> logs += Item(f)
                else -> {}
            }
            if (f.absolutePath.startsWith(download) && now - f.lastModified() > OLD_DOWNLOAD_DAYS * 86_400_000L)
                oldDl += Item(f, "scaricato il " + Util.human(f.lastModified()).substringBefore(' '))
            if (len >= BIG) big += Item(f, Util.size(len))
            if (len > 50_000 && !inThumbs) bySize.getOrPut(len) { mutableListOf() } += f
        }
        walk(root, false)

        // doppioni: stessa dimensione e stesso contenuto (si tiene il file piu' vecchio)
        progress("Cerco i file doppi…")
        val dups = mutableListOf<Item>()
        for ((_, group) in bySize) {
            if (stop()) break
            if (group.size < 2) continue
            val byHash = group.groupBy { hash(it) }
            for ((h, same) in byHash) {
                if (h.isEmpty() || same.size < 2) continue
                val sorted = same.sortedBy { it.lastModified() }
                val keep = sorted.first()
                sorted.drop(1).forEach { dups += Item(it, "uguale a " + keep.path.removePrefix(root.path + "/")) }
            }
        }

        return listOf(
            Category("thumbs", "Miniature delle foto", "Si ricreano da sole quando servono. Sicuro da cancellare.", thumbs, true),
            Category("apk", "File di installazione (.apk)", "App già installate o scaricate: di solito non servono più.", apks, true),
            Category("log", "File temporanei e di registro", "File .log e .tmp lasciati dalle app.", logs, true),
            Category("empty", "Cartelle vuote", "Cartelle senza niente dentro.", empty, true),
            Category("dup", "File doppi", "Copie identiche dello stesso file. Si tiene il più vecchio.", dups, false),
            Category("olddl", "Download vecchi", "Scaricati più di $OLD_DOWNLOAD_DAYS giorni fa: controlla prima di cancellare.", oldDl, false),
            Category("big", "File grandi (oltre 100 MB)", "Controlla se ti servono ancora.", big.sortedByDescending { it.file.length() }, false)
        ).filter { it.items.isNotEmpty() }
    }

    private fun hash(f: File): String = try {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    /** Cancella i file scelti. Restituisce (cancellati, byte liberati). */
    fun delete(items: List<Item>): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        for (it in items) {
            val len = if (it.file.isFile) it.file.length() else 0L
            val ok = if (it.file.isDirectory) it.file.listFiles().isNullOrEmpty() && it.file.delete() else it.file.delete()
            if (ok) { n++; bytes += len }
        }
        return n to bytes
    }
}
