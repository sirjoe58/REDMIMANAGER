package it.sirjoe.redmimanager

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ricerca dei NAS (cartelle condivise SMB) nella rete di casa */
object NasFinder {
    data class Found(val host: String, val name: String)

    /** Controlla tutti gli indirizzi della rete del telefono (es. 192.168.1.1 - 254) sulla porta 445 */
    fun scan(): List<Found> {
        val ip = PcLink.localIpv4() ?: return emptyList()
        val prefix = ip.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = (1..254).map { n ->
                Callable {
                    val h = "$prefix.$n"
                    if (h != ip && Dest.portOpen(h, 445, 400)) h else null
                }
            }
            val hosts = pool.invokeAll(tasks, 30, TimeUnit.SECONDS).mapNotNull {
                try { it.get() } catch (_: Exception) { null }
            }
            return hosts.map { h ->
                val name = try {
                    InetAddress.getByName(h).canonicalHostName.takeIf { it != h } ?: ""
                } catch (_: Exception) { "" }
                Found(h, name)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Cartelle condivise del NAS (con o senza utente/password) */
    fun shares(host: String, user: String, password: String, domain: String = ""): List<String> {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val ctx = if (user.isBlank()) base.withGuestCrendentials()
        else base.withCredentials(NtlmPasswordAuthenticator(domain.ifBlank { null }, user, password))
        try {
            SmbFile("smb://$host/", ctx).use { root ->
                return root.list().map { it.trimEnd('/') }.filter { !it.endsWith("$") }
            }
        } finally {
            try { ctx.close() } catch (_: Exception) {}
        }
    }
}
