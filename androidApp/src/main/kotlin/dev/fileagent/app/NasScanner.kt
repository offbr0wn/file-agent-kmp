package dev.fileagent.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import dev.fileagent.fs.Smb
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A file-sharing device found on the Wi-Fi. */
data class FoundNas(val name: String, val host: String)

/** What signing in to a NAS gave back: its shares, or a short reason it failed. */
data class ShareLookup(val shares: List<String>, val error: String? = null, val serverName: String? = null)

/**
 * Finds NAS drives on the local network, the way File Manager+ does:
 * listens for SMB services (mDNS `_smb._tcp`), and also probes the
 * common NetBIOS/SMB port across the phone's subnet as a fallback.
 */
class NasScanner(private val context: Context) {
    private val _found = MutableStateFlow<List<FoundNas>>(emptyList())
    val found: StateFlow<List<FoundNas>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var scope: CoroutineScope? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        _found.value = emptyList()
        announced.clear(); probed.clear(); showProbed = false
        router = gateway()
        _scanning.value = true
        startNsd(s)
        s.launch { probeSubnet() }
        // Give announcements a few seconds before falling back to the port scan.
        s.launch { delay(4_000); showProbed = true; publish() }
        s.launch { delay(12_000); _scanning.value = false }
    }

    fun stop() {
        runCatching { listener?.let { nsd().stopServiceDiscovery(it) } }
        listener = null
        scope?.cancel()
        scope = null
        _scanning.value = false
    }

    private fun nsd() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Drives that announce themselves as file servers (mDNS), and hosts that only have the SMB port open.
    private val announced = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val probed = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var router: String? = null
    @Volatile private var showProbed = false

    /**
     * NAS boxes (Synology, UGREEN, QNAP, TrueNAS...) announce themselves on the Wi-Fi, so when any
     * do, only those are shown. PCs and the router also share files, so the plain port scan is
     * only a fallback, and the router is never shown.
     */
    private fun publish() {
        val pick = if (announced.isNotEmpty()) announced else if (showProbed) probed else emptyMap()
        _found.value = pick.filterKeys { it != router }.map { (host, name) -> FoundNas(name, host) }.sortedBy { it.name.lowercase() }
    }

    private fun add(name: String, host: String) {
        announced[host] = name
        publish()
    }

    private fun startNsd(s: CoroutineScope) {
        val manager = nsd()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                s.launch { resolve(manager, serviceInfo) }
            }
        }
        listener = l
        runCatching { manager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        val host = withTimeoutOrNull(5_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null) {}
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(ipv4Of(serviceInfo)) {}
                    }
                })
            }
        } ?: return
        add(info.serviceName, host)
    }

    /** The IPv4 address of a found service. IPv6 link-local addresses do not work for SMB here. */
    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val all = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return all.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }

    /** Checks every address on the phone's /24 for an open SMB port. Quick: 200 ms each, in parallel. */
    private suspend fun probeSubnet() {
        val mine = localAddress() ?: return
        val prefix = mine.substringBeforeLast('.')
        val jobs = (1..254).map { n ->
            val ip = "$prefix.$n"
            scope!!.launch {
                if (ip == mine || ip == router) return@launch
                val open = runCatching {
                    Socket().use { it.connect(InetSocketAddress(ip, 445), 250); true }
                }.getOrDefault(false)
                if (open) { probed[ip] = netbiosName(ip) ?: ip; publish() }
            }
        }
        jobs.forEach { it.join() }
    }

    /** The device's own name (NetBIOS, like SHASHANK-NAS); falls back to reverse DNS. */
    private fun netbiosName(ip: String): String? =
        nbstat(ip) ?: runCatching {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }?.substringBefore('.')
        }.getOrNull()


    /** The router's address, so it can be left out. */
    private fun gateway(): String? = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
    }.getOrNull()

    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        /**
         * Signs in to a NAS and lists its shares.
         * Sign-in errors come back as a short reason. If sign-in works but the NAS
         * will not list shares, the list is empty with no error, so the user can type one.
         */
        suspend fun listShares(host: String, user: String, password: String): ShareLookup = withContext(Dispatchers.IO) {
            try {
                Smb.client().connect(host.trim()).use { conn ->
                    val session = conn.authenticate(Smb.auth(user, password))
                    val shares = runCatching {
                        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                        ServerService(transport).shares0
                            .map { it.netName }
                            .filter { !it.endsWith("$") }
                            .sorted()
                    }.getOrDefault(emptyList())
                    ShareLookup(shares, serverName = serverNameOf(conn) ?: nbstat(host.trim()))
                }
            } catch (e: Throwable) {
                ShareLookup(emptyList(), reason(e))
            }
        }

        private fun reason(e: Throwable): String {
            var t: Throwable? = e
            while (t != null) {
                when (t) {
                    is SMBApiException -> return when (t.status) {
                        NtStatus.STATUS_LOGON_FAILURE -> "Wrong username or password"
                        NtStatus.STATUS_ACCOUNT_DISABLED -> "This account can't sign in"
                        NtStatus.STATUS_ACCESS_DENIED -> "The NAS said no access"
                        NtStatus.STATUS_PASSWORD_EXPIRED -> "Password has expired"
                        else -> "NAS error: ${t.status}"
                    }
                    is java.net.UnknownHostException -> return "Can't find that address"
                    is java.net.ConnectException, is java.net.NoRouteToHostException -> return "Can't reach the NAS. Same Wi-Fi?"
                    is java.net.SocketTimeoutException -> return "The NAS took too long to answer"
                }
                t = t.cause
            }
            val msg = e.message.orEmpty()
            return when {
                "SMB1" in msg || "dialect" in msg.lowercase() -> "Turn on SMB 2 or 3 on the NAS"
                msg.isNotBlank() -> "Couldn't sign in: ${msg.take(80)}"
                else -> "Couldn't sign in"
            }
        }
    }
}

    /** NetBIOS "node status" query on UDP 137: the same way Windows and file apps learn a device's name. */
internal fun nbstat(ip: String): String? = runCatching {
        val q = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0x13, 0x37, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            write(0x20)
            // "*" padded to 16 bytes, each half-byte written as 'A' + value.
            val nm = ByteArray(16).also { it[0] = '*'.code.toByte() }
            nm.forEach { b -> val v = b.toInt() and 0xff; write('A'.code + (v shr 4)); write('A'.code + (v and 0xf)) }
            write(0)
            write(byteArrayOf(0, 0x21, 0, 1))
        }.toByteArray()
        java.net.DatagramSocket().use { sock ->
            sock.soTimeout = 700
            sock.send(java.net.DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val pkt = java.net.DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            val count = buf[56].toInt() and 0xff
            (0 until count).map { 57 + it * 18 }.firstNotNullOfOrNull { off ->
                if (off + 18 > pkt.length) return@firstNotNullOfOrNull null
                val suffix = buf[off + 15].toInt() and 0xff
                val group = (buf[off + 16].toInt() and 0x80) != 0
                if (suffix == 0 && !group) String(buf, off, 15, Charsets.US_ASCII).trim() else null
            }?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

/** True when something answers on the file-sharing port: the NAS is on and on this network. */
internal fun nasReachable(host: String): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(host, 445), 700); true }
}.getOrDefault(false)

/** The name the NAS gives itself during sign-in, read loosely so library changes can't break the build. */
private fun serverNameOf(conn: Any): String? = runCatching {
    val ctx = conn.javaClass.methods.firstOrNull { it.name == "getConnectionContext" || it.name == "getConnectionInfo" }
        ?.invoke(conn) ?: return null
    (ctx.javaClass.methods.firstOrNull { it.name == "getNetBiosName" }?.invoke(ctx) as? String)?.takeIf { it.isNotBlank() }
}.getOrNull()
