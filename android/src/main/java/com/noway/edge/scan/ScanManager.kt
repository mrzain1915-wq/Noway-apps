package com.noway.edge.scan

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ScanManager {
    private lateinit var appContext: Context
    private lateinit var persistence: ScanPersistence
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ScanSnapshot())
    val state: StateFlow<ScanSnapshot> = mutableState.asStateFlow()
    private val pauseRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var scanJob: Job? = null

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        persistence = ScanPersistence(appContext)
        persistence.load()?.let { loaded ->
            val interrupted = loaded.state in setOf(ScanState.PREPARING, ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING)
            val checkpoint = if (interrupted) loaded.copy(state = ScanState.PAUSED).also(persistence::save) else loaded
            mutableState.value = snapshot(checkpoint, persistence.recent(checkpoint.jobId), if (interrupted) "Recovered checkpoint — ready to resume" else "Checkpoint ready")
        }
    }

    fun start(config: ScanConfig) {
        config.validate()
        require(scanJob?.isActive != true) { "Another scan is already active" }
        val total = TargetParser.estimate(config)
        val jobId = System.currentTimeMillis()
        persistence.start(jobId, config, total, System.currentTimeMillis())
        mutableState.value = ScanSnapshot(jobId, config.name, ScanState.PREPARING, total = total, message = "Preparing durable scan plan")
        launchService(ScanForegroundService.ACTION_START)
    }

    fun resume() = launchService(ScanForegroundService.ACTION_RESUME)
    fun pause() { pauseRequested.set(true); mutableState.value = mutableState.value.copy(state = ScanState.PAUSING, message = "Pausing after active checks finish") }
    fun stop() { stopRequested.set(true); mutableState.value = mutableState.value.copy(message = "Ending after active checks finish") }

    fun history(): List<ScanPersistence.Checkpoint> = persistence.history()

    fun clearHistory() {
        require(scanJob?.isActive != true) { "Pause or end the active scan before clearing history" }
        persistence.clearAll()
        mutableState.value = ScanSnapshot()
    }

    fun select(jobId: Long) {
        require(scanJob?.isActive != true) { "Pause the active scan before opening another job" }
        var checkpoint = persistence.select(jobId)
        if (checkpoint.state in setOf(ScanState.PREPARING, ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING)) {
            checkpoint = checkpoint.copy(state = ScanState.PAUSED)
            persistence.save(checkpoint)
        }
        mutableState.value = snapshot(checkpoint, persistence.recent(jobId), "Saved job opened")
    }

    private fun launchService(action: String) {
        val intent = Intent(appContext, ScanForegroundService::class.java).setAction(action)
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun constraintReason(config: ScanConfig): String? {
        if (config.requireWifi) {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return "Waiting for Wi-Fi before continuing"
            }
        }
        if (config.requireCharging) {
            val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            if (!battery.isCharging) return "Waiting until the device is charging"
        }
        return null
    }

    internal fun runCheckpoint() {
        if (scanJob?.isActive == true) return
        val checkpoint = persistence.load() ?: return
        pauseRequested.set(false); stopRequested.set(false)
        scanJob = scope.launch { execute(checkpoint) }
    }

    private suspend fun execute(initial: ScanPersistence.Checkpoint) {
        var checkpoint = initial.copy(state = ScanState.RUNNING)
        persistence.save(checkpoint)
        val errors = checkpoint.errors.toMutableMap()
        val checked = AtomicLong(checkpoint.checked); val matched = AtomicLong(checkpoint.matched); val cdn = AtomicLong(checkpoint.cdn)
        val started = if (checkpoint.startedAt > 0) checkpoint.startedAt else System.currentTimeMillis()
        val recentResults = persistence.recent(checkpoint.jobId).toMutableList()
        mutableState.value = snapshot(checkpoint, recentResults, "Scanning authorized targets")
        val units = TargetParser.units(checkpoint.config, checkpoint.cursor).iterator()
        val batchSize = maxOf(16, checkpoint.config.workers * 2)
        // Dispatchers.IO is normally capped below the Custom worker ceiling. A limited,
        // elastic view makes the selected mobile worker count the real probe ceiling.
        val workerDispatcher = Dispatchers.IO.limitedParallelism(checkpoint.config.workers)
        val concurrency = Semaphore(checkpoint.config.workers)
        try {
            while (units.hasNext() && !stopRequested.get() && !pauseRequested.get()) {
                var reason = constraintReason(checkpoint.config)
                while (reason != null && !stopRequested.get() && !pauseRequested.get()) {
                    if (checkpoint.state != ScanState.WAITING) {
                        checkpoint = checkpoint.copy(state = ScanState.WAITING)
                        persistence.save(checkpoint)
                    }
                    mutableState.value = snapshot(checkpoint, recentResults, reason)
                    delay(3_000)
                    reason = constraintReason(checkpoint.config)
                }
                if (stopRequested.get() || pauseRequested.get()) break
                if (checkpoint.state == ScanState.WAITING) {
                    checkpoint = checkpoint.copy(state = ScanState.RUNNING)
                    persistence.save(checkpoint)
                }
                val batch = ArrayList<ScanUnit>(batchSize)
                repeat(batchSize) { if (units.hasNext()) batch += units.next() }
                val results = coroutineScope {
                    batch.mapIndexed { index, unit ->
                        async(workerDispatcher) {
                            if (checkpoint.config.rateLimit > 0) {
                                delay(index * 1000L / checkpoint.config.rateLimit)
                            }
                            concurrency.withPermit {
                                var result = NetworkProbe.probe(unit, checkpoint.config)
                                repeat(checkpoint.config.retries) { attempt ->
                                    if (result.errorCategory in setOf("timeout", "network") && !stopRequested.get()) {
                                        delay(250L shl attempt)
                                        result = NetworkProbe.probe(unit, checkpoint.config)
                                    }
                                }
                                result
                            }
                        }
                    }.awaitAll()
                }
                results.forEach { result ->
                    checked.incrementAndGet(); if (result.matched) matched.incrementAndGet(); if (result.cdnProvider != "Unknown") cdn.incrementAndGet()
                    if (result.errorCategory.isNotBlank()) errors[result.errorCategory] = (errors[result.errorCategory] ?: 0) + 1
                    if (result.matched || checkpoint.config.storeAll || result.cdnProvider != "Unknown") {
                        persistence.append(checkpoint.jobId, result, checkpoint.config.saveHeaders)
                        recentResults.add(0, result)
                        if (recentResults.size > 500) recentResults.removeAt(recentResults.lastIndex)
                    }
                }
                val cursor = batch.last().index + 1
                checkpoint = checkpoint.copy(cursor = cursor, checked = checked.get(), matched = matched.get(), cdn = cdn.get(), errors = errors, state = if (pauseRequested.get()) ScanState.PAUSING else ScanState.RUNNING)
                persistence.save(checkpoint)
                val elapsed = maxOf(1L, (System.currentTimeMillis() - started) / 1000)
                mutableState.value = snapshot(checkpoint, recentResults.toList(), "${checked.get()} checks • ${matched.get()} matches").copy(current = results.lastOrNull()?.target.orEmpty(), checksPerSecond = checked.get().toDouble() / elapsed, elapsedSeconds = elapsed)
            }
            val finalState = when { stopRequested.get() -> ScanState.CANCELLED; pauseRequested.get() -> ScanState.PAUSED; else -> ScanState.COMPLETED }
            checkpoint = checkpoint.copy(state = finalState, checked = checked.get(), matched = matched.get(), cdn = cdn.get(), errors = errors)
            persistence.save(checkpoint)
            mutableState.value = snapshot(checkpoint, recentResults.toList(), when (finalState) { ScanState.PAUSED -> "Checkpoint saved — tap Resume when ready"; ScanState.COMPLETED -> "Scan completed and evidence saved"; else -> "Session ended; progress remains saved" })
        } catch (error: Exception) {
            checkpoint = checkpoint.copy(state = ScanState.FAILED, errors = errors + ("internal" to ((errors["internal"] ?: 0) + 1)))
            persistence.save(checkpoint)
            mutableState.value = snapshot(checkpoint, recentResults.toList(), error.message ?: "Unexpected scanner error")
        } finally {
            appContext.stopService(Intent(appContext, ScanForegroundService::class.java))
        }
    }

    private fun snapshot(value: ScanPersistence.Checkpoint, results: List<ScanResult>, message: String) = ScanSnapshot(value.jobId, value.config.name, value.state, value.checked, value.total, value.matched, value.cdn, value.errors, results = results, message = message)

    fun exportCsv(): File = persistence.exportCsv(state.value.jobId, File(appContext.cacheDir, "exports/NOWAY-results-${state.value.jobId}.csv"))
}
