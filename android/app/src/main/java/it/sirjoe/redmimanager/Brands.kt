package it.sirjoe.redmimanager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings

/**
 * Marche di telefono supportate. Per ognuna: dove il "backup di fabbrica" salva i suoi file
 * (che contengono anche i dati delle app) e quale app di sistema lo esegue.
 */
data class Brand(
    val id: String,
    val name: String,
    /** cartelle (relative alla memoria del telefono) create dal backup di fabbrica */
    val factoryPaths: List<String>,
    /** app di sistema che fa il backup di fabbrica, in ordine di preferenza */
    val backupPackages: List<String>,
    /** come fare il backup di fabbrica */
    val howToBackup: String,
    /** come rimettere i dati con il backup di fabbrica */
    val howToRestore: String,
    /** parole che compaiono in Build.MANUFACTURER / BRAND */
    val match: List<String>
)

object Brands {
    const val DEFAULT = "xiaomi"

    val ALL = listOf(
        Brand(
            "xiaomi", "Xiaomi / Redmi / POCO",
            listOf("MIUI/backup/AllBackup", "MIUI/backup"),
            listOf("com.miui.backup"),
            "Impostazioni › Impostazioni aggiuntive (o \"Informazioni sul telefono\") › Backup e ripristino › " +
                "Backup dispositivo › Esegui il backup. Scegli app e dati e attendi la fine. " +
                "I file finiscono in MIUI/backup/AllBackup e Redmi Manager li copia al backup successivo.",
            "1) Ripristina con Redmi Manager i \"File\" (contengono la cartella MIUI/backup/AllBackup). " +
                "2) Apri Impostazioni › Backup e ripristino › Backup dispositivo › Ripristina e scegli la data.",
            listOf("xiaomi", "redmi", "poco")
        ),
        Brand(
            "samsung", "Samsung",
            listOf("SmartSwitch", "SmartSwitchBackup"),
            listOf("com.sec.android.easyMover"),
            "Apri Smart Switch › Trasferimento su memoria esterna (scheda SD o chiavetta USB) › Backup. " +
                "Se fai il backup su scheda SD, aggiungi la cartella SmartSwitch tra le \"Altre cartelle\". " +
                "In alternativa usa Smart Switch per PC.",
            "Ripristina con Redmi Manager i \"File\" (cartella SmartSwitch), poi apri Smart Switch › " +
                "Trasferimento su memoria esterna › Ripristina.",
            listOf("samsung")
        ),
        Brand(
            "huawei", "Huawei / Honor",
            listOf("Huawei/Backup", "HuaweiBackup", "Honor/Backup", "HonorBackup"),
            listOf("com.huawei.KoBackup", "com.hihonor.KoBackup"),
            "Impostazioni › Sistema e aggiornamenti › Backup e ripristino › Backup dati › Memoria interna " +
                "(o Memoria USB). Scegli app e dati e premi Backup.",
            "Ripristina con Redmi Manager i \"File\" (cartella Huawei/Backup), poi Impostazioni › Sistema › " +
                "Backup e ripristino › Backup dati › Ripristina.",
            listOf("huawei", "honor")
        ),
        Brand(
            "oppo", "OPPO / realme / OnePlus",
            listOf("Backup", "opbackup", "ColorOS/Backup"),
            listOf("com.coloros.backuprestore", "com.oneplus.backuprestore", "com.heytap.backuprestore"),
            "Impostazioni › Impostazioni aggiuntive › Backup e migrazione › Backup locale › Nuovo backup.",
            "Ripristina con Redmi Manager i \"File\", poi Impostazioni › Impostazioni aggiuntive › " +
                "Backup e migrazione › Backup locale › scegli il backup › Ripristina.",
            listOf("oppo", "realme", "oneplus")
        ),
        Brand(
            "vivo", "vivo / iQOO",
            listOf("vivo/backup", "Backup"),
            listOf("com.vivo.easyshare", "com.bbk.cloud"),
            "Apri EasyShare › Backup telefono (o Impostazioni › Account › Backup e ripristino).",
            "Ripristina con Redmi Manager i \"File\", poi EasyShare › Ripristina.",
            listOf("vivo", "iqoo")
        ),
        Brand(
            "motorola", "Motorola",
            emptyList(), emptyList(),
            "Motorola non ha un backup locale dei dati delle app: Redmi Manager salva tutto il resto.",
            "Ripristina con Redmi Manager. Accedi di nuovo alle app dopo averle reinstallate.",
            listOf("motorola", "lenovo")
        ),
        Brand(
            "pixel", "Google Pixel",
            emptyList(), emptyList(),
            "I Pixel non hanno un backup locale dei dati delle app: Redmi Manager salva tutto il resto senza usare Google.",
            "Ripristina con Redmi Manager. Accedi di nuovo alle app dopo averle reinstallate.",
            listOf("google")
        ),
        Brand(
            "nothing", "Nothing / CMF",
            emptyList(), emptyList(),
            "Questo telefono non ha un backup locale dei dati delle app: Redmi Manager salva tutto il resto.",
            "Ripristina con Redmi Manager. Accedi di nuovo alle app dopo averle reinstallate.",
            listOf("nothing")
        ),
        Brand(
            "altro", "Altra marca Android",
            emptyList(), emptyList(),
            "Se il tuo telefono ha un \"backup locale\" nelle Impostazioni, eseguilo e aggiungi la sua cartella " +
                "tra le \"Altre cartelle\": Redmi Manager la copierà.",
            "Ripristina con Redmi Manager i \"File\", poi usa il ripristino locale del telefono.",
            emptyList()
        )
    )

    fun get(id: String): Brand = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** Marca riconosciuta dal telefono stesso (per un suggerimento al primo avvio) */
    fun detect(): Brand? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return ALL.firstOrNull { b -> b.match.any { m.contains(it) } }
    }

    /** Apre l'app/schermata del backup di fabbrica. Restituisce false se non trovata. */
    fun openFactoryBackup(ctx: Context, b: Brand): Boolean {
        for (pkg in b.backupPackages) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            false
        } catch (_: Exception) {
            false
        }
    }
}
