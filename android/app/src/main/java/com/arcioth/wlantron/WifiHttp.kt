package com.arcioth.wlantron

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP pinned to the phone's Wi-Fi network.
 *
 * [Network.openConnection] asks the public DNS name first and often ignores
 * the timeout, so login sat on "Looking for Wi-Fi" and Check reported
 * detectportal.firefox.com. Sockets are bound with [Network.bindSocket],
 * and the Wi-Fi gateway is probed before any public name.
 *
 * FortiGate answers the portal with a certificate the phone does not trust.
 * The desktop program turns verification off for the same reason.
 */
class WifiHttp(private val network: Network) : HttpCall {
    override fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): HttpResult {
        val uri = URI(url)
        val host = uri.host ?: throw java.io.IOException("Bad URL")
        val https = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
        }
        val address = resolve(host, timeoutMs)
        val plain = Socket()
        var active: Socket = plain
        try {
            network.bindSocket(plain)
            plain.soTimeout = timeoutMs
            plain.connect(InetSocketAddress(address, port), timeoutMs)
            if (https) {
                val ssl = trustAll.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                active = ssl
            }
            val hostHeader = if (port == (if (https) 443 else 80)) host else "$host:$port"
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(hostHeader).append("\r\n")
            head.append("User-Agent: ").append(USER_AGENT).append("\r\n")
            head.append("Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\n")
            head.append("Cache-Control: no-cache, no-store\r\n")
            head.append("Pragma: no-cache\r\n")
            head.append("Connection: close\r\n")
            for ((key, value) in headers) head.append(key).append(": ").append(value).append("\r\n")
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")
            val out = active.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readHttp(active.getInputStream())
        } finally {
            try {
                active.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolve(host: String, timeoutMs: Int): InetAddress {
        if (IPV4.matches(host)) return InetAddress.getByName(host)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val wait = pool.submit<InetAddress> {
                val found = network.getAllByName(host)
                found.firstOrNull { it is Inet4Address } ?: found.first()
            }
            return wait.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (exc: TimeoutException) {
            throw java.io.IOException("DNS timed out for $host")
        } catch (exc: Exception) {
            val cause = exc.cause ?: exc
            throw java.io.IOException(cause.message ?: "DNS failed for $host")
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        private val trustAll: SSLContext = SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }),
                SecureRandom(),
            )
        }

        fun wifiNetwork(context: Context): Network? {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
            return manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }

        /** Gateway addresses. The portal answers here without a public DNS lookup. */
        fun hints(context: Context, network: Network): List<String> {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val links = manager.getLinkProperties(network) ?: return emptyList()
            val ips = linkedSetOf<String>()
            for (route in links.routes) {
                val gateway = route.gateway
                if (gateway is Inet4Address && !gateway.isAnyLocalAddress) {
                    gateway.hostAddress?.let { ips += it }
                }
            }
            links.dhcpServerAddress?.hostAddress?.let { if (IPV4.matches(it)) ips += it }
            return ips.map { "http://$it/" }
        }
    }
}

private fun readHttp(input: InputStream): HttpResult {
    val raw = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var headerEnd = -1
    while (headerEnd < 0 && raw.size() < 65536) {
        val n = try {
            input.read(buf)
        } catch (exc: SocketTimeoutException) {
            if (raw.size() == 0) throw exc
            break
        }
        if (n < 0) break
        raw.write(buf, 0, n)
        headerEnd = indexOfHeaderEnd(raw.toByteArray())
    }
    if (headerEnd < 0) throw java.io.IOException("No HTTP response")
    val all = raw.toByteArray()
    val headerText = all.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
    val lines = headerText.split("\r\n")
    val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw java.io.IOException(lines.first().ifBlank { "Bad HTTP status" })
    val headers = linkedMapOf<String, String>()
    val cookies = mutableListOf<String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        if (key == "set-cookie") cookies += value.substringBefore(';')
        if (key !in headers) headers[key] = value
    }
    val body = ByteArrayOutputStream()
    if (headerEnd + 4 < all.size) body.write(all, headerEnd + 4, all.size - headerEnd - 4)
    val length = headers["content-length"]?.toIntOrNull()
    val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    try {
        while (body.size() < 262144 && (length == null || body.size() < length)) {
            val n = input.read(buf)
            if (n < 0) break
            body.write(buf, 0, n)
        }
    } catch (_: SocketTimeoutException) {
    }
    var bytes = body.toByteArray()
    if (!chunked && length != null && bytes.size > length) bytes = bytes.copyOf(length)
    if (chunked) bytes = decodeChunks(bytes)
    return HttpResult(status, headers, cookies, bytes)
}

private fun indexOfHeaderEnd(bytes: ByteArray): Int {
    for (i in 0 until bytes.size - 3) {
        if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
            bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
        ) {
            return i
        }
    }
    return -1
}

private fun decodeChunks(raw: ByteArray): ByteArray {
    val text = raw.toString(Charsets.ISO_8859_1)
    val out = ByteArrayOutputStream()
    var pos = 0
    while (pos < text.length) {
        val lineEnd = text.indexOf("\r\n", pos)
        if (lineEnd < 0) break
        val size = text.substring(pos, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: break
        if (size == 0) break
        val start = lineEnd + 2
        val end = start + size
        if (end > text.length) {
            out.write(text.substring(start).toByteArray(Charsets.ISO_8859_1))
            break
        }
        out.write(text.substring(start, end).toByteArray(Charsets.ISO_8859_1))
        pos = end + 2
    }
    return if (out.size() == 0) raw else out.toByteArray()
}
