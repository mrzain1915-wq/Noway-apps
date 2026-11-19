package com.noway.edge.scan

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.Type

object NetworkProbe {
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val tlsSubject: String,
        val tlsIssuer: String,
        val tlsExpiry: String,
        val tlsAlpn: String,
        val websocketVerified: Boolean,
    )

    private fun insecureFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
    }

    private fun socket(unit: ScanUnit, config: ScanConfig): Pair<Socket, SSLSocket?> {
        val raw = Socket().apply {
            connect(InetSocketAddress(unit.connectHost, unit.port), config.timeoutMs)
            soTimeout = config.timeoutMs
            tcpNoDelay = true
        }
        if (unit.scheme !in setOf("https", "wss")) return raw to null
        try {
            val identity = config.sni.ifBlank { unit.requestHost }
            val factory = if (config.verifyTls) SSLSocketFactory.getDefault() as SSLSocketFactory else insecureFactory()
            val tls = factory.createSocket(raw, identity, unit.port, true) as SSLSocket
            tls.soTimeout = config.timeoutMs
            tls.enabledProtocols = tls.enabledProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            tls.sslParameters = tls.sslParameters.apply {
                if (config.verifyTls) endpointIdentificationAlgorithm = "HTTPS"
                if (Build.VERSION.SDK_INT >= 24 && ':' !in identity && identity.any(Char::isLetter)) {
                    serverNames = listOf(SNIHostName(identity))
                }
                if (Build.VERSION.SDK_INT >= 29) applicationProtocols = arrayOf("http/1.1")
            }
            tls.startHandshake()
            return tls to tls
        } catch (error: Exception) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun requestHeaders(unit: ScanUnit, config: ScanConfig): Map<String, String> = linkedMapOf(
        "Host" to unit.requestHost.let { host ->
            val formatted = if (':' in host && !host.startsWith('[')) "[$host]" else host
            if (unit.port in setOf(80, 443)) formatted else "$formatted:${unit.port}"
        },
        "User-Agent" to "NOWAY-CDN-CIDR-Scanner/1.0 Android",
        "Accept" to "*/*",
        "Accept-Encoding" to "identity",
        "Connection" to "close",
    ) + config.headers

    private fun readHeaders(input: BufferedInputStream): Pair<Int, Map<String, String>> {
        val bytes = ArrayList<Byte>(4096)
        var match = 0
        while (bytes.size <= 65_536) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            match = when {
                match == 0 && value == 13 -> 1
                match == 1 && value == 10 -> 2
                match == 2 && value == 13 -> 3
                match == 3 && value == 10 -> 4
                value == 13 -> 1
                else -> 0
            }
            if (match == 4) break
        }
        require(match == 4) { "HTTP header terminator is missing" }
        val block = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = block.split("\r\n")
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status line")
        val headers = linkedMapOf<String, String>()
        lines.drop(1).filter { ':' in it }.forEach { line ->
            val name = line.substringBefore(':').trim(); val value = line.substringAfter(':').trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        return status to headers
    }

    private fun cnameChain(host: String): List<String> {
        if (!host.any(Char::isLetter)) return emptyList()
        val chain = mutableListOf<String>()
        val seen = mutableSetOf(host.lowercase().trimEnd('.'))
        var current = host
        repeat(8) {
            val record = runCatching { Lookup(current, Type.CNAME).run() }
                .getOrNull()
                ?.filterIsInstance<CNAMERecord>()
                ?.firstOrNull()
                ?: return chain
            val target = record.target.toString().trimEnd('.')
            if (!seen.add(target.lowercase())) return chain
            chain += target
            current = target
        }
        return chain
    }

    private fun doRequest(unit: ScanUnit, config: ScanConfig, method: String, websocket: Boolean): Response {
        val (transport, tls) = socket(unit, config)
        transport.use { active ->
            val key = if (websocket) Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP) else ""
            val headers = requestHeaders(unit, config).toMutableMap()
            if (websocket) headers += mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Key" to key, "Sec-WebSocket-Version" to "13")
            val wire = buildString {
                append(if (websocket) "GET" else method).append(' ').append(unit.path).append(" HTTP/1.1\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            BufferedOutputStream(active.getOutputStream()).apply { write(wire.toByteArray(Charsets.ISO_8859_1)); flush() }
            val (status, responseHeaders) = readHeaders(BufferedInputStream(active.getInputStream()))
            val certificate = tls?.session?.peerCertificates?.firstOrNull() as? X509Certificate
            val expectedAccept = if (websocket) Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()), Base64.NO_WRAP) else ""
            val accept = responseHeaders.entries.firstOrNull { it.key.equals("Sec-WebSocket-Accept", true) }?.value.orEmpty()
            val upgrade = responseHeaders.entries.firstOrNull { it.key.equals("Upgrade", true) }?.value.orEmpty()
            return Response(
                status,
                responseHeaders,
                certificate?.subjectX500Principal?.name.orEmpty(),
                certificate?.issuerX500Principal?.name.orEmpty(),
                certificate?.notAfter?.toInstant()?.toString().orEmpty(),
                if (Build.VERSION.SDK_INT >= 29) tls?.applicationProtocol.orEmpty() else "",
                websocket && status == 101 && accept == expectedAccept && upgrade.equals("websocket", true),
            )
        }
    }

    suspend fun probe(unit: ScanUnit, config: ScanConfig): ScanResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val base = ScanResult(unit.index, unit.display, unit.scheme, unit.requestHost, unit.connectHost, unit.port, unit.path)
        try {
            val addresses = InetAddress.getAllByName(unit.connectHost).toList()
            val ips = addresses.mapNotNull { it.hostAddress }.distinct()
            val cnames = cnameChain(unit.connectHost)
            val websocket = unit.scheme in setOf("ws", "wss")
            var response = doRequest(unit, config, config.method.let { if (it == "SMART") "HEAD" else it }, websocket)
            if (!websocket && config.method == "SMART" && response.status in setOf(400, 403, 405, 501)) response = doRequest(unit, config, "GET", false)
            val server = response.headers.entries.firstOrNull { it.key.equals("Server", true) }?.value.orEmpty()
            val cdn = if (config.detectCdn) CdnDetector.detect(cnames, response.headers, ips, "${response.tlsSubject} ${response.tlsIssuer}") else CdnMatch()
            val matched = response.status in config.statuses && (response.status != 101 || response.websocketVerified)
            base.copy(
                status = response.status, latencyMs = (System.nanoTime() - started) / 1_000_000,
                resolvedIps = ips, cnameChain = cnames, server = server, headers = response.headers,
                tlsSubject = response.tlsSubject, tlsIssuer = response.tlsIssuer, tlsExpiry = response.tlsExpiry,
                tlsAlpn = response.tlsAlpn, cdnProvider = cdn.provider, cdnConfidence = cdn.confidence,
                cdnEvidence = cdn.evidence, websocketVerified = response.websocketVerified, matched = matched,
                timestamp = Instant.now().toString(),
            )
        } catch (error: Exception) {
            val category = when (error) {
                is SocketTimeoutException -> "timeout"
                is java.net.UnknownHostException -> "dns"
                is ConnectException -> "refused"
                is javax.net.ssl.SSLException -> "tls"
                else -> "network"
            }
            base.copy(latencyMs = (System.nanoTime() - started) / 1_000_000, errorCategory = category, error = error.message ?: error.javaClass.simpleName, timestamp = Instant.now().toString())
        }
    }
}
