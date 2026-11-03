package com.arcioth.wlantron

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP that stays on the phone's Wi-Fi network.
 *
 * [Network.openConnection] is the Android replacement for Linux
 * SO_BINDTODEVICE. Mobile data or a VPN can own the default route and this
 * connection still leaves through Wi-Fi.
 *
 * FortiGate answers the portal with a certificate the phone does not trust.
 * The desktop program turns verification off for the same reason. That
 * applies only to this client.
 */
class WifiHttp(private val network: Network) : HttpCall {
    override fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): HttpResult {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("Pragma", "no-cache")
            for ((key, value) in headers) conn.setRequestProperty(key, value)
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = trustAll.socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val headerMap = LinkedHashMap<String, String>()
            val cookies = mutableListOf<String>()
            for ((key, values) in conn.headerFields) {
                if (key == null) continue
                val lower = key.lowercase()
                headerMap[lower] = values.joinToString(", ")
                if (lower == "set-cookie") {
                    for (value in values) {
                        val pair = value.substringBefore(";").trim()
                        if (pair.isNotEmpty()) cookies += pair
                    }
                }
            }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = if (stream == null) ByteArray(0) else readLimited(stream, 262144)
            return HttpResult(status, headerMap, cookies, bytes)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"

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
    }
}

private fun readLimited(stream: InputStream, limit: Int): ByteArray {
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = input.read(chunk, 0, minOf(chunk.size, limit - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
