package com.noway.edge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.noway.edge.scan.MobilePerformanceProfile
import com.noway.edge.scan.ScanConfig
import com.noway.edge.scan.ScanManager
import com.noway.edge.scan.ScanResult
import com.noway.edge.scan.ScanSnapshot
import com.noway.edge.scan.ScanState
import com.noway.edge.scan.TargetParser
import com.noway.edge.scan.requiresHighLoadConfirmation
import com.noway.edge.ui.NowayTheme
import com.noway.edge.ui.SignalAmber
import com.noway.edge.ui.SignalBlue
import com.noway.edge.ui.SignalCyan
import com.noway.edge.ui.SignalGreen
import com.noway.edge.ui.SignalRed

class MainActivity : ComponentActivity() {
    private var importedTargets by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            var dark by rememberSaveable { mutableStateOf(true) }
            NowayTheme(dark) {
                val openTargets = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        runCatching {
                            val text = contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } ?: error("The selected file could not be read")
                            require(text.length <= 100 * 1024 * 1024) { "Target file exceeds the 100 MB safety limit" }
                            normalizeImportedTargets(text)
                        }.onSuccess { targets -> importedTargets = targets }
                            .onFailure { error -> Toast.makeText(this, error.message ?: "Import failed", Toast.LENGTH_LONG).show() }
                    }
                }
                NowayUi(importedTargets, { openTargets.launch(arrayOf("text/plain", "text/csv", "text/*")) }, { shareExport() }, dark, { dark = it })
            }
        }
    }

    private fun shareExport() {
        val file = ScanManager.exportCsv()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Export NOWAY evidence"))
    }
}

internal fun parseCsvRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        when {
            character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                cell.append('"')
                index++
            }
            character == '"' -> quoted = !quoted
            character == ',' && !quoted -> {
                cells += cell.toString().trim()
                cell.clear()
            }
            else -> cell.append(character)
        }
        index++
    }
    cells += cell.toString().trim()
    return cells
}

internal fun normalizeImportedTargets(content: String): String {
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    if (lines.isEmpty()) return ""
    val header = parseCsvRow(lines.first()).map { it.trim().lowercase() }
    val column = listOf("target", "url", "host", "ip", "cidr").firstNotNullOfOrNull { header.indexOf(it).takeIf { index -> index >= 0 } }
        ?: return lines.joinToString("\n")
    return lines.drop(1).mapNotNull { line -> parseCsvRow(line).getOrNull(column)?.trim()?.takeIf(String::isNotBlank) }.joinToString("\n")
}

private enum class Screen(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Rounded.Dashboard), Scan("Scan", Icons.Rounded.Radar), Results("Results", Icons.Rounded.Dns), History("History", Icons.Rounded.History), Settings("Settings", Icons.Rounded.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowayUi(importedTargets: String, onImport: () -> Unit, onExport: () -> Unit, dark: Boolean, onDark: (Boolean) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }
    val snapshot by ScanManager.state.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("NOWAY", fontWeight = FontWeight.Black, letterSpacing = 1.5.sp); Text("CDN + CIDR SIGNAL LAB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, actions = { StatusPill(snapshot.state) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { Screen.entries.forEach { item -> NavigationBarItem(selected = screen == item, onClick = { screen = item }, icon = { Icon(item.icon, null) }, label = { Text(item.label, maxLines = 1) }) } } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (screen) {
                Screen.Dashboard -> DashboardScreen(snapshot, { screen = Screen.Scan }, { screen = Screen.Results })
                Screen.Scan -> ScanScreen(importedTargets, onImport, snapshot)
                Screen.Results -> ResultsScreen(snapshot, onExport)
                Screen.History -> HistoryScreen(snapshot, { screen = Screen.Results })
                Screen.Settings -> SettingsScreen(dark, onDark)
            }
        }
    }
}

@Composable
private fun StatusPill(state: ScanState) {
    val color = when (state) { ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING -> SignalCyan; ScanState.COMPLETED -> SignalGreen; ScanState.FAILED -> SignalRed; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Row(Modifier.padding(end = 12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)); Text(state.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardScreen(snapshot: ScanSnapshot, newScan: () -> Unit, results: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Signal dashboard", "A live operating picture for authorized edge and response checks.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("CHECKS", snapshot.checked.toString(), "durable progress", SignalCyan, Modifier.weight(1f))
                MetricCard("MATCHES", snapshot.matched.toString(), "101 / 200 / 204", SignalGreen, Modifier.weight(1f))
                MetricCard("CDN SIGNALS", snapshot.cdnDetected.toString(), "evidence backed", SignalAmber, Modifier.weight(1f))
                MetricCard("SPEED", "%.1f/s".format(snapshot.checksPerSecond), "live throughput", SignalBlue, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)); Column { Text(snapshot.name, fontWeight = FontWeight.SemiBold); Text(snapshot.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    Spacer(Modifier.height(14.dp)); val progress = if (snapshot.total > 0) (snapshot.checked.toFloat() / snapshot.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape))
                    Text("${snapshot.checked} of ${snapshot.total} checks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(newScan, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(7.dp)); Text("New scan") }
                OutlinedButton(results, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(7.dp)); Text("Evidence") }
            }
        }
        item { SectionTitle("LATEST SIGNALS", "Only matches or evidence-bearing responses are stored") }
        items(snapshot.results.take(6)) { ResultCard(it, compact = true) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MetricCard(label: String, value: String, detail: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier.width(165.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row { Box(Modifier.width(4.dp).fillMaxHeight().height(94.dp).background(color)); Column(Modifier.padding(13.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp)); Text(detail, style = MaterialTheme.typography.labelSmall, color = color) } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanScreen(importedTargets: String, onImport: () -> Unit, snapshot: ScanSnapshot) {
    var targets by rememberSaveable(importedTargets) { mutableStateOf(importedTargets.ifBlank { "example.com\n192.0.2.0/30" }) }
    var name by rememberSaveable { mutableStateOf("NOWAY edge intelligence") }; var ports by rememberSaveable { mutableStateOf("80,443") }; var path by rememberSaveable { mutableStateOf("/") }
    var http by rememberSaveable { mutableStateOf(true) }; var https by rememberSaveable { mutableStateOf(true) }; var ws by rememberSaveable { mutableStateOf(false) }; var wss by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf("SMART") }; var statuses by rememberSaveable { mutableStateOf("101,200,204") }; var timeout by rememberSaveable { mutableStateOf("5") }; var workers by rememberSaveable { mutableStateOf("32") }; var rate by rememberSaveable { mutableStateOf("100") }; var retries by rememberSaveable { mutableStateOf("1") }
    var performance by rememberSaveable { mutableStateOf(MobilePerformanceProfile.BALANCED.name) }
    var host by rememberSaveable { mutableStateOf("") }; var sni by rememberSaveable { mutableStateOf("") }; var headers by rememberSaveable { mutableStateOf("") }; var verifyTls by rememberSaveable { mutableStateOf(true) }; var saveHeaders by rememberSaveable { mutableStateOf(false) }; var storeAll by rememberSaveable { mutableStateOf(false) }; var requireWifi by rememberSaveable { mutableStateOf(false) }; var requireCharging by rememberSaveable { mutableStateOf(false) }; var authorized by rememberSaveable { mutableStateOf(false) }; var advanced by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }; var pending by remember { mutableStateOf<ScanConfig?>(null) }
    val active = snapshot.state in setOf(ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING, ScanState.PREPARING)
    val selectedPerformance = MobilePerformanceProfile.valueOf(performance)

    fun config(): ScanConfig {
        val customHeaders = headers.lines().filter(String::isNotBlank).associate { line -> require(':' in line) { "Custom header must be Name: Value" }; line.substringBefore(':').trim() to line.substringAfter(':').trim() }
        val timeoutSeconds = timeout.toDoubleOrNull() ?: throw IllegalArgumentException("Timeout is required")
        val workerCount = workers.toIntOrNull() ?: throw IllegalArgumentException("Workers must be a whole number")
        val checksPerSecond = rate.toIntOrNull() ?: throw IllegalArgumentException("Rate must be a whole number")
        val retryCount = retries.toIntOrNull() ?: throw IllegalArgumentException("Retries must be a whole number")
        val matchingStatuses = statuses.split(Regex("[\\s,]+")).filter(String::isNotBlank).map { value -> value.toIntOrNull() ?: throw IllegalArgumentException("Statuses must be whole numbers") }
        return ScanConfig(name, targets.lines().map(String::trim).filter(String::isNotBlank), TargetParser.parsePorts(ports), buildList { if (http) add("http"); if (https) add("https"); if (ws) add("ws"); if (wss) add("wss") }, path, method, host, sni, customHeaders, statuses = matchingStatuses, timeoutMs = (timeoutSeconds * 1000).toInt(), workers = workerCount, rateLimit = checksPerSecond, retries = retryCount, verifyTls = verifyTls, saveHeaders = saveHeaders, storeAll = storeAll, requireWifi = requireWifi, requireCharging = requireCharging, authorized = authorized).also(ScanConfig::validate)
    }

    fun startOrConfirm(built: ScanConfig) {
        error = ""
        val total = TargetParser.estimate(built)
        if (built.requiresHighLoadConfirmation(total)) pending = built
        else runCatching { ScanManager.start(built) }.onFailure { failure -> error = failure.message ?: "The scan could not start" }
    }

    pending?.let { highLoadConfig ->
        val highLoadChecks = TargetParser.estimate(highLoadConfig)
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Rounded.Shield, null) },
            title = { Text("Confirm high-load authorized scan") },
            text = { Text("This plan contains $highLoadChecks checks at ${highLoadConfig.workers} workers and ${highLoadConfig.rateLimit} checks/sec. It may heat the phone, use significant battery, and create substantial network load. Continue only if every target is authorized.") },
            confirmButton = { Button({ runCatching { ScanManager.start(highLoadConfig) }.onSuccess { pending = null }.onFailure { failure -> pending = null; error = failure.message ?: "The scan could not start" } }) { Text("Start scan") } },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Build an authorized scan", "Paste targets or import TXT/CSV. Settings become a resumable checkpoint.")
        OutlinedTextField(targets, { targets = it }, label = { Text("Targets") }, supportingText = { Text("URL, hostname, IP, inclusive range, or CIDR — one per line") }, minLines = 5, maxLines = 9, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        TextButton(onImport) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Import TXT / CSV") }
        OutlinedTextField(name, { name = it }, label = { Text("Job name") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 9.dp)) { OutlinedTextField(ports, { ports = it }, label = { Text("Ports") }, modifier = Modifier.weight(1f)); OutlinedTextField(path, { path = it }, label = { Text("Path") }, modifier = Modifier.weight(1f)) }
        Text("PROTOCOLS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("HTTP" to http, "HTTPS" to https, "WS" to ws, "WSS" to wss).forEach { (label, selected) -> FilterChip(selected, { when (label) { "HTTP" -> http = !http; "HTTPS" -> https = !https; "WS" -> ws = !ws; else -> wss = !wss } }, { Text(label) }) } }
        Text("PROBE MODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("SMART", "HEAD", "GET").forEach { item -> FilterChip(method == item, { method = item }, { Text(item) }) } }
        Text("MOBILE PERFORMANCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MobilePerformanceProfile.entries.forEach { profile ->
                FilterChip(
                    selected = selectedPerformance == profile,
                    onClick = {
                        performance = profile.name
                        profile.workers?.let { workers = it.toString() }
                        profile.rateLimit?.let { rate = it.toString() }
                        error = ""
                    },
                    label = { Text(profile.label) },
                )
            }
        }
        OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedPerformance.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("$workers workers • $rate checks/s", style = MaterialTheme.typography.labelMedium, color = if (selectedPerformance in setOf(MobilePerformanceProfile.HEAVY, MobilePerformanceProfile.EXTREME, MobilePerformanceProfile.CUSTOM)) SignalAmber else MaterialTheme.colorScheme.primary)
                }
                Text(selectedPerformance.guidance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 8.dp)) {
            NumericField(timeout, { timeout = it }, "Timeout s", Modifier.weight(1f), allowDecimal = true)
            NumericField(workers, { workers = it; performance = MobilePerformanceProfile.CUSTOM.name }, "Workers", Modifier.weight(1f))
            NumericField(rate, { rate = it; performance = MobilePerformanceProfile.CUSTOM.name }, "Rate/s", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Advanced edge routing", fontWeight = FontWeight.SemiBold) }
        AnimatedVisibility(advanced) { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { OutlinedTextField(host, { host = it }, label = { Text("Host header") }, modifier = Modifier.weight(1f)); OutlinedTextField(sni, { sni = it }, label = { Text("TLS SNI") }, modifier = Modifier.weight(1f)) }; OutlinedTextField(statuses, { statuses = it.filter { character -> character.isDigit() || character == ',' || character.isWhitespace() } }, label = { Text("Matching statuses") }, supportingText = { Text("Example: 101, 200, 204") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(headers, { headers = it }, label = { Text("Custom headers") }, supportingText = { Text("One Name: Value per line") }, minLines = 3, modifier = Modifier.fillMaxWidth()); NumericField(retries, { retries = it }, "Retries", Modifier.fillMaxWidth()); SettingToggle("Verify TLS certificates", verifyTls) { verifyTls = it }; SettingToggle("Run on Wi-Fi only", requireWifi) { requireWifi = it }; SettingToggle("Run only while charging", requireCharging) { requireCharging = it }; SettingToggle("Save redacted response headers", saveHeaders) { saveHeaders = it }; SettingToggle("Store all responses (not just matches/CDN)", storeAll) { storeAll = it } } }
        OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Row(Modifier.clickable { authorized = !authorized }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(authorized, { authorized = it }); Column { Text("I am authorized to check every target", fontWeight = FontWeight.SemiBold); Text("Required before a scan can start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            if (!active) Button({ runCatching { config() }.onSuccess { built -> startOrConfirm(built) }.onFailure { failure -> error = failure.message ?: "Check the scan settings" } }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (snapshot.state == ScanState.PAUSED) "New scan" else "Start scan") }
            if (active) OutlinedButton({ ScanManager.pause() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Pause, null); Text("Pause") }
            if (snapshot.state == ScanState.PAUSED) Button({ ScanManager.resume() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Text("Resume") }
            if (active) OutlinedButton({ ScanManager.stop() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Stop, null); Text("End") }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun NumericField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier, allowDecimal: Boolean = false) = OutlinedTextField(value, { candidate -> if (candidate.all { character -> character.isDigit() || (allowDecimal && character == '.') } && (!allowDecimal || candidate.count { it == '.' } <= 1)) onValue(candidate) }, label = { Text(label) }, singleLine = true, modifier = modifier)

@Composable
private fun ResultsScreen(snapshot: ScanSnapshot, onExport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var cdnOnly by rememberSaveable { mutableStateOf(false) }; var matchesOnly by rememberSaveable { mutableStateOf(false) }
    val filtered = snapshot.results.filter { result -> (!cdnOnly || result.cdnProvider != "Unknown") && (!matchesOnly || result.matched) && (query.isBlank() || listOf(result.target, result.connectHost, result.server, result.cdnProvider, result.status.toString()).any { it.contains(query, true) }) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Evidence explorer", "Search, filter, copy, and export stored response evidence.")
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Search results") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 9.dp)) { FilterChip(cdnOnly, { cdnOnly = !cdnOnly }, { Text("CDN only") }, leadingIcon = { Icon(Icons.Rounded.Cloud, null, Modifier.size(17.dp)) }); FilterChip(matchesOnly, { matchesOnly = !matchesOnly }, { Text("Matches") }, leadingIcon = { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(17.dp)) }); Spacer(Modifier.weight(1f)); IconButton(onExport, enabled = snapshot.results.isNotEmpty()) { Icon(Icons.Rounded.Download, "Export CSV") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) { items(filtered, key = { "${it.unitIndex}-${it.timestamp}" }) { ResultCard(it) }; item { Spacer(Modifier.height(15.dp)) } }
    }
}

@Composable
private fun ResultCard(result: ScanResult, compact: Boolean = false) {
    val context = LocalContext.current
    val statusColor = when { result.matched -> SignalGreen; result.status != null -> SignalAmber; else -> SignalRed }
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(result.status?.toString() ?: result.errorCategory.uppercase(), color = statusColor, fontWeight = FontWeight.Black, modifier = Modifier.width(58.dp)); Column(Modifier.weight(1f)) { Text("${result.scheme}://${result.connectHost}:${result.port}${result.path}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 12.sp); Text("${result.latencyMs} ms • ${result.resolvedIps.firstOrNull().orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton({ (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NOWAY result", result.toJson(true).toString(2))) }) { Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(18.dp)) } }
            if (result.cdnProvider != "Unknown") Row(Modifier.padding(top = 8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Cloud, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)); Text("${result.cdnProvider}  ${result.cdnConfidence}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            if (!compact && result.cdnEvidence.isNotEmpty()) Text(result.cdnEvidence.joinToString(" • "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
            if (!compact && result.error.isNotBlank()) Text(result.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun HistoryScreen(snapshot: ScanSnapshot, open: () -> Unit) {
    val jobs = ScanManager.history()
    val active = snapshot.state in setOf(ScanState.PREPARING, ScanState.RUNNING, ScanState.WAITING, ScanState.PAUSING)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Durable job history", "Every local checkpoint and its evidence remains available across app restarts.") }
        if (jobs.isEmpty()) item { Text("No scan jobs yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(jobs, key = { it.jobId }) { job ->
            OutlinedCard(border = BorderStroke(1.dp, if (job.jobId == snapshot.jobId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(job.config.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Job ${job.jobId} • ${job.state.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    HorizontalDivider(Modifier.padding(vertical = 11.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { Text("${job.checked}/${job.total} checks", style = MaterialTheme.typography.labelMedium); Text("${job.matched} matches", style = MaterialTheme.typography.labelMedium, color = SignalGreen); Text("${job.cdn} CDN", style = MaterialTheme.typography.labelMedium, color = SignalAmber) }
                    if (job.errors.isNotEmpty()) Text(job.errors.entries.joinToString(" • ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 11.dp)) {
                        OutlinedButton({ ScanManager.select(job.jobId); open() }, enabled = !active, modifier = Modifier.weight(1f)) { Text("Open evidence") }
                        if (job.state == ScanState.PAUSED) Button({ ScanManager.select(job.jobId); ScanManager.resume() }, enabled = !active, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Text("Resume") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun SettingsScreen(dark: Boolean, onDark: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Settings & privacy", "Appearance, safety boundaries, and build information.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp)) { SettingToggle("Deep signal dark theme", dark, onDark); HorizontalDivider(Modifier.padding(vertical = 9.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Shield, null, tint = SignalGreen); Spacer(Modifier.width(10.dp)); Column { Text("Local-first evidence", fontWeight = FontWeight.SemiBold); Text("No account, ads, analytics, or remote control", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
        SectionTitle("SAFETY CONTRACT", "Built for authorized infrastructure diagnostics")
        listOf("Authorization is required for every new scan.", "Large scan plans require a second confirmation.", "Cookies and authentication challenges are redacted.", "No exploit, credential attack, proxy bypass, or remote control is included.").forEach { item -> Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) { Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)); Spacer(Modifier.width(9.dp)); Text(item, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text("NOWAY CDN + CIDR Scanner 2.3.0\nAndroid 6.0+ • Windows • Termux", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 18.dp))
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onChecked) }

@Composable
private fun KeyValue(key: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(value, fontWeight = FontWeight.SemiBold) }

@Composable
private fun SectionTitle(title: String, subtitle: String) = Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 9.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
