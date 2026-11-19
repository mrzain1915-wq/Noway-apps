package com.noway.edge.scan

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

class ScanPersistence(context: Context) {
    private val root = File(context.filesDir, "scan-data").apply { mkdirs() }
    private val jobs = File(root, "jobs").apply { mkdirs() }
    private val resultSets = File(root, "results").apply { mkdirs() }
    private val activeJob = File(root, "active-job.txt")

    data class Checkpoint(
        val jobId: Long,
        val config: ScanConfig,
        val state: ScanState,
        val cursor: Long,
        val total: Long,
        val checked: Long,
        val matched: Long,
        val cdn: Long,
        val errors: Map<String, Long>,
        val startedAt: Long,
    )

    private fun jobFile(jobId: Long) = File(jobs, "$jobId.json")
    private fun resultFile(jobId: Long) = File(resultSets, "$jobId.jsonl")

    @Synchronized
    fun start(jobId: Long, config: ScanConfig, total: Long, startedAt: Long) {
        resultFile(jobId).writeText("")
        activeJob.writeText(jobId.toString())
        save(Checkpoint(jobId, config, ScanState.PREPARING, 0, total, 0, 0, 0, emptyMap(), startedAt))
    }

    @Synchronized
    fun select(jobId: Long): Checkpoint {
        val checkpoint = load(jobId) ?: error("Job $jobId does not exist")
        activeJob.writeText(jobId.toString())
        return checkpoint
    }

    @Synchronized
    fun save(value: Checkpoint) {
        val json = JSONObject().apply {
            put("jobId", value.jobId)
            put("config", value.config.toJson())
            put("state", value.state.name)
            put("cursor", value.cursor)
            put("total", value.total)
            put("checked", value.checked)
            put("matched", value.matched)
            put("cdn", value.cdn)
            put("errors", JSONObject(value.errors))
            put("startedAt", value.startedAt)
            put("updatedAt", Instant.now().toString())
        }
        val destination = jobFile(value.jobId)
        val temporary = File(jobs, "${value.jobId}.tmp")
        temporary.writeText(json.toString())
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) { "Unable to save scan checkpoint" }
    }

    private fun read(file: File): Checkpoint? = runCatching {
        val json = JSONObject(file.readText())
        val errorsJson = json.optJSONObject("errors") ?: JSONObject()
        Checkpoint(
            json.getLong("jobId"),
            ScanConfig.fromJson(json.getJSONObject("config")),
            ScanState.valueOf(json.getString("state")),
            json.getLong("cursor"),
            json.getLong("total"),
            json.getLong("checked"),
            json.getLong("matched"),
            json.getLong("cdn"),
            errorsJson.keys().asSequence().associateWith { errorsJson.getLong(it) },
            json.getLong("startedAt"),
        )
    }.getOrNull()

    @Synchronized
    fun load(jobId: Long? = null): Checkpoint? {
        val selected = jobId ?: activeJob.takeIf(File::exists)?.readText()?.trim()?.toLongOrNull()
        return selected?.let { read(jobFile(it)) }
    }

    @Synchronized
    fun history(limit: Int = 100): List<Checkpoint> = jobs.listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull(::read)
        .sortedByDescending(Checkpoint::startedAt)
        .take(limit.coerceIn(1, 500))

    @Synchronized
    fun append(jobId: Long, result: ScanResult, saveHeaders: Boolean) {
        resultFile(jobId).appendText(result.toJson(saveHeaders).toString() + "\n")
    }

    @Synchronized
    fun recent(jobId: Long, limit: Int = 500): List<ScanResult> {
        val results = resultFile(jobId)
        val bounded = limit.coerceIn(1, 5_000)
        return if (!results.exists()) emptyList() else results.useLines { lines ->
            val tail = ArrayDeque<String>(bounded)
            lines.filter(String::isNotBlank).forEach { line ->
                tail.addLast(line)
                if (tail.size > bounded) tail.removeFirst()
            }
            tail.toList().asReversed().map { ScanResult.fromJson(JSONObject(it)) }
        }
    }

    @Synchronized
    fun exportCsv(jobId: Long, destination: File): File {
        destination.parentFile?.mkdirs()
        val results = resultFile(jobId)
        destination.bufferedWriter().use { out ->
            out.appendLine("target,status,scheme,host,connect_host,port,path,latency_ms,resolved_ips,cname_chain,server,tls_issuer,tls_expiry,tls_alpn,cdn_provider,cdn_confidence,cdn_evidence,websocket_verified,matched,error_category,error,timestamp")
            if (results.exists()) results.useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    val result = ScanResult.fromJson(JSONObject(line))
                    val cells = listOf(
                        result.target, result.status ?: "", result.scheme, result.host,
                        result.connectHost, result.port, result.path, result.latencyMs,
                        result.resolvedIps.joinToString(" | "), result.cnameChain.joinToString(" | "),
                        result.server, result.tlsIssuer, result.tlsExpiry, result.tlsAlpn,
                        result.cdnProvider, result.cdnConfidence, result.cdnEvidence.joinToString(" | "),
                        result.websocketVerified, result.matched, result.errorCategory, result.error,
                        result.timestamp,
                    )
                    out.appendLine(cells.joinToString(",") { "\"${it.toString().replace("\"", "\"\"")}\"" })
                }
            }
        }
        return destination
    }
}
