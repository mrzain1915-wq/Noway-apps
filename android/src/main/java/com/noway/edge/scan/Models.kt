package com.noway.edge.scan

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class ScanState { DRAFT, PREPARING, RUNNING, WAITING, PAUSING, PAUSED, COMPLETED, CANCELLED, FAILED }

const val MAX_MOBILE_WORKERS = 256

enum class MobilePerformanceProfile(
    val label: String,
    val workers: Int?,
    val rateLimit: Int?,
    val guidance: String,
) {
    ECO("Eco", 8, 20, "Lowest battery and heat use for long background jobs."),
    SAFE("Safe", 16, 50, "Cool, steady scanning for older or low-memory phones."),
    BALANCED("Balanced", 32, 100, "Recommended default for most recent Android phones."),
    FAST("Fast", 48, 160, "Higher throughput with moderate battery and heat use."),
    HEAVY("Heavy", 64, 220, "Best while charging; the phone may become warm."),
    EXTREME("Extreme", 96, 320, "For high-end phones on strong Wi-Fi and active cooling."),
    CUSTOM("Custom", null, null, "Set 1-256 workers and 0-2000 checks/sec manually."),
    ;

    val isCustom: Boolean get() = workers == null || rateLimit == null
}

data class ScanConfig(
    val name: String = "NOWAY edge intelligence",
    val sources: List<String> = emptyList(),
    val ports: List<Int> = listOf(80, 443),
    val schemes: List<String> = listOf("http", "https"),
    val path: String = "/",
    val method: String = "SMART",
    val hostHeader: String = "",
    val sni: String = "",
    val headers: Map<String, String> = emptyMap(),
    val statuses: List<Int> = listOf(101, 200, 204),
    val timeoutMs: Int = 5_000,
    val workers: Int = 32,
    val rateLimit: Int = 100,
    val retries: Int = 1,
    val verifyTls: Boolean = true,
    val saveHeaders: Boolean = false,
    val storeAll: Boolean = false,
    val detectCdn: Boolean = true,
    val requireWifi: Boolean = false,
    val requireCharging: Boolean = false,
    val authorized: Boolean = false,
) {
    fun validate() {
        require(authorized) { "Authorization confirmation is required" }
        require(sources.isNotEmpty()) { "Add at least one URL, host, IP, range, or CIDR" }
        require(ports.isNotEmpty() && ports.all { it in 1..65535 }) { "Ports must be 1-65535" }
        require(schemes.isNotEmpty() && schemes.all { it in setOf("http", "https", "ws", "wss") }) { "Choose HTTP, HTTPS, WS, or WSS" }
        require(path.startsWith('/') && !path.contains('#') && path.length <= 4096 && path.none { it == '\r' || it == '\n' }) { "Path must start with / and contain no fragment or line break" }
        listOf("Host header" to hostHeader, "TLS SNI" to sni).forEach { (label, value) ->
            require(value.length <= 253 && value.none { it == '\r' || it == '\n' }) { "$label is invalid" }
        }
        require(method in setOf("SMART", "HEAD", "GET")) { "Invalid probe method" }
        require(timeoutMs in 200..120_000) { "Timeout must be 0.2-120 seconds" }
        require(workers in 1..MAX_MOBILE_WORKERS) { "Workers must be 1-$MAX_MOBILE_WORKERS" }
        require(rateLimit in 0..2_000) { "Rate limit must be 0-2000 checks/sec" }
        require(retries in 0..5) { "Retries must be 0-5" }
        require(statuses.isNotEmpty() && statuses.all { it in 100..599 }) { "Statuses must be 100-599" }
        require(headers.size <= 50 && headers.entries.sumOf { it.key.length + it.value.length + 4 } <= 8192) { "Custom headers must be at most 50 lines / 8 KB" }
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && ':' !in name && name.all { it.code in 33..126 }) { "Invalid header name: $name" }
            require('\r' !in value && '\n' !in value) { "Header contains a control character: $name" }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("sources", JSONArray(sources))
        put("ports", JSONArray(ports))
        put("schemes", JSONArray(schemes))
        put("path", path)
        put("method", method)
        put("hostHeader", hostHeader)
        put("sni", sni)
        put("headers", JSONObject(headers))
        put("statuses", JSONArray(statuses))
        put("timeoutMs", timeoutMs)
        put("workers", workers)
        put("rateLimit", rateLimit)
        put("retries", retries)
        put("verifyTls", verifyTls)
        put("saveHeaders", saveHeaders)
        put("storeAll", storeAll)
        put("detectCdn", detectCdn)
        put("requireWifi", requireWifi)
        put("requireCharging", requireCharging)
        put("authorized", authorized)
    }

    companion object {
        fun fromJson(value: JSONObject) = ScanConfig(
            name = value.optString("name", "NOWAY edge intelligence"),
            sources = value.getJSONArray("sources").strings(),
            ports = value.getJSONArray("ports").ints(),
            schemes = value.getJSONArray("schemes").strings(),
            path = value.optString("path", "/"),
            method = value.optString("method", "SMART"),
            hostHeader = value.optString("hostHeader", ""),
            sni = value.optString("sni", ""),
            headers = value.optJSONObject("headers")?.let { json -> json.keys().asSequence().associateWith { json.getString(it) } } ?: emptyMap(),
            statuses = value.optJSONArray("statuses")?.ints() ?: listOf(101, 200, 204),
            timeoutMs = value.optInt("timeoutMs", 5_000),
            workers = value.optInt("workers", 32),
            rateLimit = value.optInt("rateLimit", 100),
            retries = value.optInt("retries", 1),
            verifyTls = value.optBoolean("verifyTls", true),
            saveHeaders = value.optBoolean("saveHeaders", false),
            storeAll = value.optBoolean("storeAll", false),
            detectCdn = value.optBoolean("detectCdn", true),
            requireWifi = value.optBoolean("requireWifi", false),
            requireCharging = value.optBoolean("requireCharging", false),
            authorized = value.optBoolean("authorized", false),
        )
    }
}

fun ScanConfig.requiresHighLoadConfirmation(totalChecks: Long): Boolean =
    totalChecks > 1_000_000 || workers > MobilePerformanceProfile.EXTREME.workers!! || rateLimit > 500

data class ScanUnit(
    val index: Long,
    val display: String,
    val connectHost: String,
    val requestHost: String,
    val scheme: String,
    val port: Int,
    val path: String,
)

data class CdnMatch(val provider: String = "Unknown", val confidence: Int = 0, val evidence: List<String> = emptyList())

data class ScanResult(
    val unitIndex: Long,
    val target: String,
    val scheme: String,
    val host: String,
    val connectHost: String,
    val port: Int,
    val path: String,
    val status: Int? = null,
    val latencyMs: Long = 0,
    val resolvedIps: List<String> = emptyList(),
    val cnameChain: List<String> = emptyList(),
    val server: String = "",
    val headers: Map<String, String> = emptyMap(),
    val tlsSubject: String = "",
    val tlsIssuer: String = "",
    val tlsExpiry: String = "",
    val tlsAlpn: String = "",
    val cdnProvider: String = "Unknown",
    val cdnConfidence: Int = 0,
    val cdnEvidence: List<String> = emptyList(),
    val websocketVerified: Boolean = false,
    val matched: Boolean = false,
    val errorCategory: String = "",
    val error: String = "",
    val timestamp: String = Instant.now().toString(),
) {
    fun toJson(saveHeaders: Boolean): JSONObject = JSONObject().apply {
        put("unitIndex", unitIndex)
        put("target", target)
        put("scheme", scheme)
        put("host", host)
        put("connectHost", connectHost)
        put("port", port)
        put("path", path)
        put("status", status ?: JSONObject.NULL)
        put("latencyMs", latencyMs)
        put("resolvedIps", JSONArray(resolvedIps))
        put("cnameChain", JSONArray(cnameChain))
        put("server", server)
        if (saveHeaders) put("headers", JSONObject(headers.mapValues { (name, value) -> if (name.lowercase() in setOf("set-cookie", "www-authenticate", "proxy-authenticate", "authorization")) "[redacted]" else value }))
        put("tlsSubject", tlsSubject)
        put("tlsIssuer", tlsIssuer)
        put("tlsExpiry", tlsExpiry)
        put("tlsAlpn", tlsAlpn)
        put("cdnProvider", cdnProvider)
        put("cdnConfidence", cdnConfidence)
        put("cdnEvidence", JSONArray(cdnEvidence))
        put("websocketVerified", websocketVerified)
        put("matched", matched)
        put("errorCategory", errorCategory)
        put("error", error.take(1000))
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(value: JSONObject) = ScanResult(
            unitIndex = value.getLong("unitIndex"),
            target = value.getString("target"),
            scheme = value.getString("scheme"),
            host = value.getString("host"),
            connectHost = value.getString("connectHost"),
            port = value.getInt("port"),
            path = value.optString("path", "/"),
            status = if (value.isNull("status")) null else value.getInt("status"),
            latencyMs = value.optLong("latencyMs"),
            resolvedIps = value.optJSONArray("resolvedIps")?.strings() ?: emptyList(),
            cnameChain = value.optJSONArray("cnameChain")?.strings() ?: emptyList(),
            server = value.optString("server"),
            headers = value.optJSONObject("headers")?.let { json -> json.keys().asSequence().associateWith { json.getString(it) } } ?: emptyMap(),
            tlsSubject = value.optString("tlsSubject"),
            tlsIssuer = value.optString("tlsIssuer"),
            tlsExpiry = value.optString("tlsExpiry"),
            tlsAlpn = value.optString("tlsAlpn"),
            cdnProvider = value.optString("cdnProvider", "Unknown"),
            cdnConfidence = value.optInt("cdnConfidence"),
            cdnEvidence = value.optJSONArray("cdnEvidence")?.strings() ?: emptyList(),
            websocketVerified = value.optBoolean("websocketVerified"),
            matched = value.optBoolean("matched"),
            errorCategory = value.optString("errorCategory"),
            error = value.optString("error"),
            timestamp = value.optString("timestamp"),
        )
    }
}

data class ScanSnapshot(
    val jobId: Long = 0,
    val name: String = "No active scan",
    val state: ScanState = ScanState.DRAFT,
    val checked: Long = 0,
    val total: Long = 0,
    val matched: Long = 0,
    val cdnDetected: Long = 0,
    val errors: Map<String, Long> = emptyMap(),
    val current: String = "",
    val checksPerSecond: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val results: List<ScanResult> = emptyList(),
    val message: String = "Ready for an authorized scan",
)

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }
