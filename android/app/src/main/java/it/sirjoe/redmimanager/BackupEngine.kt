package it.sirjoe.redmimanager

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Stato del backup/ripristino in corso, letto dalla schermata principale */
object Status {
    @Volatile var running = false
    @Volatile var starting = false
    @Volatile var title = ""
    @Volatile var detail = ""
    @Volatile var percent = -1
    @Volatile var cancel = false

    fun set(t: String, d: String = "", p: Int = -1) {
        title = t; detail = d; percent = p
    }
}

/**
 * Esegue il backup verso una o piu' destinazioni.
 *
 * Struttura sulla destinazione:
 *   RedmiManager/<telefono>/telefono.json          informazioni sul telefono
 *   RedmiManager/<telefono>/Dati/<data_ora>/...     rubrica, SMS, chiamate, calendario, elenco app (copie datate)
 *   RedmiManager/<telefono>/App/<pacchetto_vers>/   file di installazione delle app
 *   RedmiManager/<telefono>/Archivio/...            foto, video, documenti, WhatsApp... (solo i file nuovi)
 */
class BackupEngine(
    private val ctx: Context,
    private val s: Settings,
    private val log: AppLog = AppLog(ctx),
    private val stopped: () -> Boolean = { Status.cancel }
) {
    companion object {
        const val ROOT = "RedmiManager"
        private val SKIP_DIRS = setOf(".thumbnails", ".trash", ".Trash", ".Statuses", ".cache", "cache", ".tmp",
            ".Trash-1000", "Android/data", "Android/obb")
        private val SKIP_PREFIX = listOf(".trashed-", ".pending-")

        fun hasFileAccess(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Cartelle del telefono salvate per ogni voce */
        fun rootsFor(item: String, brand: Brand): List<String> = when (item) {
            "foto" -> listOf("DCIM", "Pictures", "Movies")
            "documenti" -> listOf("Documents", "Download")
            "musica" -> listOf("Music", "Recordings", "MIUI/sound_recorder", "Ringtones", "Notifications")
            "whatsapp" -> listOf(
                "Android/media/com.whatsapp/WhatsApp", "WhatsApp",
                "Android/media/com.whatsapp.w4b/WhatsApp Business", "WhatsApp Business"
            )
            "fabbrica" -> brand.factoryPaths
            else -> emptyList()
        }
    }

    class Result(val ok: List<String>, val failed: List<String>, val unreachable: List<String>) {
        val summary: String
            get() = buildString {
                if (ok.isNotEmpty()) append("Backup completato su: ").append(ok.joinToString(", "))
                if (failed.isNotEmpty()) { if (isNotEmpty()) append(". "); append("Non riuscito su: ").append(failed.joinToString(", ")) }
                if (unreachable.isNotEmpty()) { if (isNotEmpty()) append(". "); append("Non raggiungibile: ").append(unreachable.joinToString(", ")) }
                if (isEmpty()) append("Nessuna destinazione attiva")
            }
    }

    private class Stop : Exception("Backup interrotto")

    private fun checkStop() {
        if (stopped()) throw Stop()
    }

    private val base get() = Util.join(ROOT, s.deviceName)

    /** Elenco dei file da mettere nell'Archivio: percorso relativo -> file */
    fun collectFiles(): LinkedHashMap<String, File> {
        val brand = Brands.get(s.brand)
        val roots = LinkedHashSet<String>()
        for (item in listOf("foto", "documenti", "musica", "whatsapp", "fabbrica"))
            if (s.item(item)) roots += rootsFor(item, brand)
        roots += s.extraFolders.map { Util.cleanRel(it) }.filter { it.isNotEmpty() }
        val out = LinkedHashMap<String, File>()
        val storage = Util.storageRoot
        for (r in roots) {
            val dir = File(storage, r)
            if (!dir.exists()) continue
            walk(dir, Util.cleanRel(r), out)
        }
        return out
    }

    private fun walk(f: File, rel: String, out: MutableMap<String, File>) {
        if (stopped()) return
        if (f.isDirectory) {
            if (f.name in SKIP_DIRS || rel in SKIP_DIRS) return
            f.listFiles()?.forEach { walk(it, "$rel/${it.name}", out) }
        } else if (f.isFile && f.canRead() && SKIP_PREFIX.none { f.name.startsWith(it) } && !f.name.endsWith(".parziale")) {
            out[rel] = f
        }
    }

    /** Esegue il backup su tutte le destinazioni indicate (o su quelle attive) */
    fun run(destinations: List<Dest> = Dest.enabled(ctx, s)): Result {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        Status.cancel = false
        Status.running = true
        Status.starting = false
        try {
            // 1) quali destinazioni sono raggiungibili adesso
            val ready = mutableListOf<Dest>()
            for (d in destinations) {
                Status.set("Controllo ${d.label}…")
                try {
                    d.check(); ready += d
                } catch (e: Exception) {
                    unreachable += d.label
                    log.add("${d.label}: ${Util.shortError(e)}")
                    d.close()
                }
            }
            if (ready.isEmpty()) return Result(ok, failed, unreachable)

            if (!hasFileAccess(ctx)) log.add("ATTENZIONE: manca il permesso \"Accesso a tutti i file\": foto e file non vengono salvati")

            // 2) esporta i dati una volta sola
            val tmp = File(ctx.cacheDir, "backup_dati").apply { deleteRecursively(); mkdirs() }
            val counts = exportData(tmp)
            checkStop()

            // 3) app e file
            val apps = if (s.item("app")) {
                Status.set("Leggo le app installate…")
                DataExport.installedApps(ctx).also { DataExport.appsJson(it, File(tmp, "app_elenco.json")); counts["app"] = it.size }
            } else emptyList()
            Status.set("Cerco foto e file…")
            val files = if (hasFileAccess(ctx)) collectFiles() else LinkedHashMap()
            checkStop()

            // 4) copia su ogni destinazione
            val stamp = Util.stamp()
            for (d in ready) {
                try {
                    log.add("Backup su ${d.label} iniziato")
                    backupTo(d, tmp, stamp, counts, apps, files)
                    s.setLastBackup(d.key, System.currentTimeMillis())
                    ok += d.label
                    log.add("Backup su ${d.label} completato")
                } catch (e: Stop) {
                    failed += d.label
                    log.add("Backup su ${d.label} interrotto")
                    break
                } catch (e: Exception) {
                    failed += d.label
                    log.add("ERRORE backup su ${d.label}: ${Util.shortError(e)}")
                } finally {
                    d.close()
                }
            }
            tmp.deleteRecursively()
        } catch (e: Stop) {
            log.add("Backup interrotto")
        } finally {
            Status.running = false
            Status.set("")
            val r = Result(ok, failed, unreachable)
            s.lastResult = Util.human(System.currentTimeMillis()) + " – " + r.summary
        }
        return Result(ok, failed, unreachable)
    }

    /** Rubrica, SMS, chiamate, calendario nella cartella temporanea. Restituisce i conteggi. */
    private fun exportData(tmp: File): MutableMap<String, Int> {
        val counts = linkedMapOf<String, Int>()
        val jobs = listOf(
            Triple("contatti", "contatti.vcf") { f: File -> DataExport.contacts(ctx, f) },
            Triple("sms", "sms.json") { f: File -> DataExport.sms(ctx, f) },
            Triple("chiamate", "chiamate.json") { f: File -> DataExport.calls(ctx, f) },
            Triple("calendario", "calendario.json") { f: File -> DataExport.calendar(ctx, f) }
        )
        for ((key, name, fn) in jobs) {
            if (!s.item(key)) continue
            checkStop()
            Status.set("Salvo ${Settings.ITEMS[key]}…")
            try {
                counts[key] = fn(File(tmp, name))
            } catch (e: Exception) {
                File(tmp, name).delete()
                log.add("${Settings.ITEMS[key]}: non salvato (${Util.shortError(e)})")
            }
        }
        return counts
    }

    private fun backupTo(
        d: Dest, tmp: File, stamp: String, counts: Map<String, Int>,
        apps: List<DataExport.AppInfo>, files: Map<String, File>
    ) {
        val snap = Util.join(base, "Dati", stamp)

        // dati (copia datata)
        val dataFiles = tmp.listFiles()?.filter { it.isFile } ?: emptyList()
        for (f in dataFiles) {
            checkStop()
            Status.set("${d.label}: dati", f.name)
            f.inputStream().use { d.write(Util.join(snap, f.name), it, f.length()) }
        }

        // app (solo le versioni non ancora salvate)
        val index = Index(ctx, d)
        var i = 0
        for (a in apps) {
            checkStop()
            i++
            Status.set("${d.label}: app $i di ${apps.size}", a.name, i * 100 / maxOf(1, apps.size))
            for (apk in a.apks) {
                val rel = Util.join(base, "App", a.folder, DataExport.apkName(apk))
                if (index.has(rel, apk)) continue
                if (d.size(rel) == apk.length()) { index.put(rel, apk); continue }
                apk.inputStream().use { d.write(rel, it, apk.length()) }
                index.put(rel, apk)
            }
        }
        index.save()

        // archivio (solo file nuovi o modificati)
        var done = 0
        var copied = 0
        var bytes = 0L
        val total = files.size
        for ((rel, f) in files) {
            checkStop()
            done++
            val target = Util.join(base, "Archivio", rel)
            if (index.has(target, f)) continue
            if (done % 20 == 0 || f.length() > 2_000_000)
                Status.set("${d.label}: file $done di $total", rel.substringAfterLast('/'), done * 100 / maxOf(1, total))
            try {
                if (d.size(target) == f.length()) {
                    index.put(target, f)
                } else {
                    f.inputStream().use { d.write(target, it, f.length()) }
                    index.put(target, f)
                    copied++
                    bytes += f.length()
                }
            } catch (e: java.io.FileNotFoundException) {
                // file sparito nel frattempo: si passa al successivo
            }
            if (done % 50 == 0) index.save()
        }
        index.save()
        if (total > 0) log.add("${d.label}: $copied file nuovi copiati (${Util.size(bytes)}), ${total - copied} gia' presenti")

        // riepilogo: scritto per ultimo, cosi' una copia senza manifest e' incompleta
        val manifest = JSONObject()
            .put("app", "Redmi Manager")
            .put("versione", 1)
            .put("telefono", s.deviceName)
            .put("modello", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("marca", s.brand)
            .put("android", Build.VERSION.RELEASE)
            .put("data", System.currentTimeMillis())
            .put("conteggi", JSONObject(counts as Map<*, *>))
            .put("file_archivio", files.size)
            .put("completo", true)
        d.writeBytes(Util.join(snap, "manifest.json"), manifest.toString(1).toByteArray())
        d.writeBytes(Util.join(base, "telefono.json"), manifest.toString(1).toByteArray())

        prune(d)
    }

    /** Conserva solo le ultime N copie dei dati */
    private fun prune(d: Dest) {
        val keep = maxOf(1, s.keepCopies)
        val dirs = d.list(Util.join(base, "Dati"))
            .filter { it.isDir && Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}").matches(it.name) }
            .map { it.name }.sorted()
        if (dirs.size <= keep) return
        for (old in dirs.dropLast(keep)) {
            try {
                d.delete(Util.join(base, "Dati", old))
                log.add("${d.label}: cancellata la copia vecchia del ${Util.stampToHuman(old)}")
            } catch (e: Exception) {
                log.add("${d.label}: non riesco a cancellare la copia $old (${Util.shortError(e)})")
            }
        }
    }

    /**
     * Elenco dei file gia' copiati su una destinazione (percorso, dimensione, data),
     * per non dover rileggere ogni volta tutta la destinazione.
     */
    private class Index(ctx: Context, d: Dest) {
        private val file = File(ctx.filesDir, "indice_${d.key}_${Integer.toHexString(d.label.hashCode())}.txt")
        private val map = HashMap<String, String>()
        private var dirty = false

        init {
            try {
                if (file.exists()) file.forEachLine { line ->
                    val i = line.indexOf('\t')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
                }
            } catch (_: Exception) {
            }
        }

        private fun sig(f: File) = "${f.length()}:${f.lastModified()}"
        fun has(rel: String, f: File) = map[rel] == sig(f)
        fun put(rel: String, f: File) {
            map[rel] = sig(f); dirty = true
        }

        fun save() {
            if (!dirty) return
            try {
                val tmp = File(file.path + ".tmp")
                tmp.bufferedWriter().use { w -> for ((k, v) in map) { w.write(k); w.write("\t"); w.write(v); w.write("\n") } }
                tmp.renameTo(file)
                dirty = false
            } catch (_: Exception) {
            }
        }
    }
}
