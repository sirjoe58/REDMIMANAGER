package it.sirjoe.redmimanager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/** Collegamento con il programma Redmi Manager per PC (Windows / Mac) nella rete di casa */
object PcLink {
    const val PORT = 47810
    const val DISCOVERY_PORT = 47811
    private const val ASK = "REDMIMANAGER_CERCA"

    data class Pc(val id: String, val name: String, val host: String, val port: Int)

    /** Cerca i PC con Redmi Manager aperto (messaggio broadcast in rete locale) */
    fun discover(ctx: Context, timeoutMs: Int = 3000): List<Pc> {
        val found = LinkedHashMap<String, Pc>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("redmimanager").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 400
                val data = ASK.toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val end = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 1000) {
                        for (t in targets) try {
                            sock.send(DatagramPacket(data, data.size, t, DISCOVERY_PORT))
                        } catch (_: Exception) {
                        }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val buf = ByteArray(2048)
                        val pk = DatagramPacket(buf, buf.size)
                        sock.receive(pk)
                        val o = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
                        if (o.optString("app") != "redmimanager") continue
                        val pc = Pc(o.optString("id"), o.optString("name", "PC"),
                            pk.address.hostAddress ?: continue, o.optInt("port", PORT))
                        found[pc.id.ifEmpty { pc.host }] = pc
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    } catch (_: Exception) {
        emptyList()
    }

    /** Indirizzo IPv4 del telefono nella rete locale (es. 192.168.1.35) */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }

    /**
     * Collega il telefono al PC con il codice a 6 cifre mostrato dal programma.
     * Se va a buon fine salva indirizzo e chiave del PC nelle impostazioni.
     */
    fun pair(ctx: Context, s: Settings, host: String, port: Int, code: String) {
        val c = URL("http://$host:$port/api/pair").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = phoneInfo(ctx, s).put("code", code.trim()).toString().toByteArray()
        c.outputStream.use { it.write(body) }
        when (c.responseCode) {
            200 -> {}
            403 -> throw IllegalStateException("Codice sbagliato o scaduto: controlla il codice sul PC")
            else -> throw IllegalStateException("Il PC ha risposto con errore ${c.responseCode}")
        }
        val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        s.pcHost = host
        s.pcPort = port
        s.pcToken = o.getString("token")
        s.pcId = o.optString("id")
        s.pcName = o.optString("name", host)
        s.pcEnabled = true
    }

    /** Contenuto del codice QR: redmimanager://pair?h=192.168.1.10,10.0.0.5&p=47810&c=123456 */
    fun pairFromQr(ctx: Context, s: Settings, text: String) {
        val u = Uri.parse(text.trim())
        if (u.scheme != "redmimanager") throw IllegalArgumentException("Questo non è il codice QR di Redmi Manager")
        val hosts = (u.getQueryParameter("h") ?: "").split(',').filter { it.isNotBlank() }
        val port = u.getQueryParameter("p")?.toIntOrNull() ?: PORT
        val code = u.getQueryParameter("c") ?: throw IllegalArgumentException("Codice QR incompleto")
        var last: Exception? = null
        for (h in hosts) {
            if (!Dest.portOpen(h, port, 1500)) continue
            try {
                pair(ctx, s, h, port, code); return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Non riesco a raggiungere il PC: telefono e PC devono essere sulla stessa rete di casa")
    }

    /** Informazioni sul telefono mostrate dal programma del PC */
    fun phoneInfo(ctx: Context, s: Settings): JSONObject {
        val st = try { StatFs(Util.storageRoot.path) } catch (_: Exception) { null }
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { "" }
        return JSONObject()
            .put("device", s.deviceName)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("brand", Brands.get(s.brand).name)
            .put("android", Build.VERSION.RELEASE)
            .put("battery", if (level >= 0) level * 100 / scale else -1)
            .put("charging", plugged)
            .put("total", st?.totalBytes ?: 0L)
            .put("free", st?.availableBytes ?: 0L)
            .put("app", ver)
            .put("ip", localIpv4() ?: "")
            .put("last_backup", maxOf(s.lastBackup("nas"), s.lastBackup("usb"), s.lastBackup("pc")))
    }

    /** Invia al PC lo stato del telefono (batteria, memoria...). Errori ignorati. */
    fun sendHello(ctx: Context, s: Settings) {
        try {
            val c = URL("http://${s.pcHost}:${s.pcPort}/api/hello").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("X-Token", s.pcToken)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(phoneInfo(ctx, s).toString().toByteArray()) }
            c.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
        }
    }
}
