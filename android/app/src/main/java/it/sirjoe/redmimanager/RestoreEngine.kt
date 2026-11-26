package it.sirjoe.redmimanager

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lettura dei backup e ripristino di file e app */
class RestoreEngine(private val ctx: Context, private val d: Dest, private val log: AppLog = AppLog(ctx)) {

    data class Snapshot(val name: String, val date: String, val complete: Boolean, val info: JSONObject?)

    /** Telefoni presenti nella destinazione */
    fun phones(): List<String> =
        d.list(BackupEngine.ROOT).filter { it.isDir }.map { it.name }.sorted()

    /** Copie datate dei dati di un telefono, dalla piu' recente */
    fun snapshots(phone: String): List<Snapshot> {
        val base = Util.join(BackupEngine.ROOT, phone, "Dati")
        return d.list(base).filter { it.isDir }.map { it.name }.sortedDescending().map { n ->
            val info = try { JSONObject(d.readText(Util.join(base, n, "manifest.json"))) } catch (_: Exception) { null }
            Snapshot(n, Util.stampToHuman(n), info?.optBoolean("completo") == true, info)
        }
    }

    fun download(rel: String, to: File): File {
        to.parentFile?.mkdirs()
        val tmp = File(to.path + ".parziale")
        d.open(rel).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        if (to.exists()) to.delete()
        if (!tmp.renameTo(to)) throw IllegalStateException("Impossibile scrivere ${to.path}")
        return to
    }

    /** Scarica un file dei dati (es. sms.json) nella cartella temporanea; null se non c'e' nel backup */
    fun dataFile(phone: String, snap: String, name: String): File? {
        val rel = Util.join(BackupEngine.ROOT, phone, "Dati", snap, name)
        if (d.size(rel) < 0) return null
        return download(rel, File(File(ctx.cacheDir, "ripristino"), name))
    }

    /** Elenco ricorsivo dell'Archivio: percorso relativo -> dimensione */
    private fun walkRemote(rel: String, prefix: String, out: MutableMap<String, Long>, stop: () -> Boolean) {
        if (stop()) return
        for (e in d.list(rel)) {
            val r = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
            if (e.isDir) walkRemote(Util.join(rel, e.name), r, out, stop)
            else if (!e.name.endsWith(".parziale")) out[r] = e.size
        }
    }

    /**
     * Rimette i file dell'Archivio nella memoria del telefono, nelle cartelle originali.
     * I file gia' presenti con la stessa dimensione non vengono riscaricati.
     * onlyRoots: se non vuoto, solo le cartelle indicate (es. la cartella del backup di fabbrica).
     */
    fun restoreFiles(
        phone: String, onlyRoots: List<String>, progress: (String, String, Int) -> Unit, stop: () -> Boolean
    ): Pair<Int, Int> {
        val base = Util.join(BackupEngine.ROOT, phone, "Archivio")
        val all = LinkedHashMap<String, Long>()
        progress("Leggo l'elenco dei file…", "", -1)
        if (onlyRoots.isEmpty()) walkRemote(base, "", all, stop)
        else for (r in onlyRoots) walkRemote(Util.join(base, r), Util.cleanRel(r), all, stop)
        var copied = 0
        var present = 0
        var i = 0
        val scan = mutableListOf<String>()
        for ((rel, size) in all) {
            if (stop()) break
            i++
            val local = File(Util.storageRoot, rel)
            if (local.exists() && local.length() == size) { present++; continue }
            progress("File $i di ${all.size}", rel.substringAfterLast('/'), i * 100 / maxOf(1, all.size))
            try {
                download(Util.join(base, rel), local)
                copied++
                scan += local.path
            } catch (e: Exception) {
                log.add("Ripristino ${rel}: ${Util.shortError(e)}")
            }
        }
        // fa comparire foto e musica nelle rispettive app
        scan.chunked(200).forEach { MediaScannerConnection.scanFile(ctx, it.toTypedArray(), null, null) }
        return copied to present
    }

    data class SavedApp(val pkg: String, val name: String, val versionCode: Long, val folder: String, val apks: List<String>)

    fun savedApps(phone: String, snap: String): List<SavedApp> {
        val f = dataFile(phone, snap, "app_elenco.json") ?: return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val a = o.getJSONArray("apks")
            SavedApp(o.getString("package"), o.optString("name"), o.optLong("version_code"), o.getString("folder"),
                (0 until a.length()).map { a.getString(it) })
        }
    }

    /** true se l'app e' gia' installata con la stessa versione o una piu' recente */
    fun installed(app: SavedApp): Boolean = try {
        val pi = ctx.packageManager.getPackageInfo(app.pkg, 0)
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        vc >= app.versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Scarica i file di installazione di un'app */
    fun downloadApp(phone: String, app: SavedApp): List<File> {
        val dir = File(ctx.cacheDir, "ripristino/app").apply { deleteRecursively(); mkdirs() }
        return app.apks.map { n -> download(Util.join(BackupEngine.ROOT, phone, "App", app.folder, n), File(dir, n)) }
    }
}
