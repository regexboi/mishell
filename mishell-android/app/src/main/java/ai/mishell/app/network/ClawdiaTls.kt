package ai.mishell.app.network

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class ClawdiaTlsConfig(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
    val hostnameVerifier: HostnameVerifier
)

fun buildClawdiaTlsConfig(expectedFingerprint: String?): ClawdiaTlsConfig {
    val expected = expectedFingerprint?.let(::normalizeFingerprint)

    val defaultTrust = defaultTrustManager()
    @SuppressLint("CustomX509TrustManager")
    val trustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                defaultTrust.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("empty certificate chain")
                if (expected == null) {
                    defaultTrust.checkServerTrusted(chain, authType)
                    return
                }

                val actual = sha256Hex(chain[0].encoded)
                if (actual != expected) {
                    throw CertificateException("gateway TLS fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrust.acceptedIssuers
        }

    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), SecureRandom())

    val hostnameVerifier = if (expected != null) {
        // Fingerprint pinning is the trust anchor when explicitly configured.
        HostnameVerifier { _, _ -> true }
    } else {
        HttpsURLConnection.getDefaultHostnameVerifier()
    }

    return ClawdiaTlsConfig(
        socketFactory = context.socketFactory,
        trustManager = trustManager,
        hostnameVerifier = hostnameVerifier
    )
}

suspend fun probeTlsFingerprint(host: String, port: Int, timeoutMs: Int = 4_000): String? {
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty() || port !in 1..65535) {
        return null
    }

    return withContext(Dispatchers.IO) {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustAll =
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), SecureRandom())

        val socket = (context.socketFactory.createSocket() as SSLSocket)
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(trimmedHost, port), timeoutMs)

            try {
                if (trimmedHost.any { it.isLetter() }) {
                    val params = SSLParameters()
                    params.serverNames = listOf(SNIHostName(trimmedHost))
                    socket.sslParameters = params
                }
            } catch (_: Throwable) {
                // no-op
            }

            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            cert?.let { sha256Hex(it.encoded) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
}

private fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        out.append(String.format(Locale.US, "%02x", byte))
    }
    return out.toString()
}

private fun normalizeFingerprint(raw: String): String {
    val stripped = raw.trim()
        .replace(Regex("^sha-?256\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
    return stripped.lowercase(Locale.US).filter { it in '0'..'9' || it in 'a'..'f' }
}
