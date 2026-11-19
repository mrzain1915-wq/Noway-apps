package com.noway.edge.scan

import java.net.URI

object TargetParser {
    data class Target(val display: String, val host: String, val scheme: String? = null, val port: Int? = null, val path: String = "/")

    fun parsePorts(text: String): List<Int> {
        val ports = sortedSetOf<Int>()
        text.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.forEach { token ->
            if ('-' in token) {
                val pair = token.split('-', limit = 2).map(String::toInt)
                require(pair[0] <= pair[1] && pair[1] - pair[0] <= 10_000) { "Invalid or oversized port range: $token" }
                ports.addAll(pair[0]..pair[1])
            } else ports += token.toInt()
        }
        require(ports.isNotEmpty() && ports.all { it in 1..65535 }) { "Ports must be between 1 and 65535" }
        return ports.toList()
    }

    private fun ipv4ToLong(text: String): Long {
        val parts = text.trim().split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $text" }
        return parts.fold(0L) { value, item ->
            val octet = item.toInt()
            require(octet in 0..255) { "Invalid IPv4 address: $text" }
            (value shl 8) or octet.toLong()
        }
    }

    private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 255).toString() }

    private fun expandSource(source: String): Sequence<Target> = sequence {
        val text = source.trim()
        if (text.isBlank() || text.startsWith('#')) return@sequence
        require(text.length <= 4096) { "Target exceeds 4096 characters" }
        if ("://" in text) {
            val uri = URI(text)
            require(uri.scheme.lowercase() in setOf("http", "https", "ws", "wss") && !uri.host.isNullOrBlank()) { "Only HTTP/HTTPS/WS/WSS URLs are supported: $text" }
            require(uri.userInfo == null && uri.fragment == null) { "URL user-info and fragments are not allowed" }
            val path = buildString { append(uri.rawPath?.ifBlank { "/" } ?: "/"); if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery) }
            yield(Target(text, uri.host, uri.scheme.lowercase(), uri.port.takeIf { it > 0 }, path))
            return@sequence
        }
        val range = Regex("^\\s*([0-9.]+)\\s*-\\s*([0-9.]+)\\s*$").matchEntire(text)
        if (range != null) {
            val start = ipv4ToLong(range.groupValues[1]); val end = ipv4ToLong(range.groupValues[2])
            require(start <= end) { "Range start must not exceed range end" }
            for (value in start..end) { val ip = longToIpv4(value); yield(Target(ip, ip)) }
            return@sequence
        }
        if ('/' in text) {
            val pair = text.split('/', limit = 2)
            require(':' !in pair[0]) { "IPv6 CIDR expansion is not supported on Android; use explicit IPv6 targets" }
            val base = ipv4ToLong(pair[0]); val prefix = pair[1].toInt()
            require(prefix in 0..32) { "CIDR prefix must be 0-32" }
            val mask = if (prefix == 0) 0 else (-1L shl (32 - prefix)) and 0xffffffffL
            val start = base and mask; val size = 1L shl (32 - prefix); val end = start + size - 1
            for (value in start..end) { val ip = longToIpv4(value); yield(Target(ip, ip)) }
            return@sequence
        }
        require(!text.any { it.isWhitespace() }) { "Invalid hostname or address: $text" }
        yield(Target(text, text.trim('[', ']')))
    }

    fun estimate(config: ScanConfig): Long = config.sources.sumOf { source ->
        val text = source.trim()
        when {
            text.isBlank() || text.startsWith('#') -> 0L
            "://" in text -> {
                val uri = URI(text)
                require(uri.scheme?.lowercase() in setOf("http", "https", "ws", "wss") && !uri.host.isNullOrBlank()) { "Only HTTP/HTTPS/WS/WSS URLs are supported: $text" }
                require(uri.userInfo == null && uri.fragment == null) { "URL user-info and fragments are not allowed" }
                1L
            }
            Regex("^\\s*([0-9.]+)\\s*-\\s*([0-9.]+)\\s*$").matches(text) -> {
                val match = Regex("^\\s*([0-9.]+)\\s*-\\s*([0-9.]+)\\s*$").matchEntire(text)!!
                val start = ipv4ToLong(match.groupValues[1]); val end = ipv4ToLong(match.groupValues[2])
                require(start <= end) { "Range start must not exceed range end" }
                (end - start + 1) * config.ports.size * config.schemes.size
            }
            '/' in text && ':' !in text -> {
                ipv4ToLong(text.substringBefore('/'))
                val prefix = text.substringAfter('/').toInt(); require(prefix in 0..32) { "CIDR prefix must be 0-32" }
                (1L shl (32 - prefix)) * config.ports.size * config.schemes.size
            }
            else -> {
                require(!text.any { it.isWhitespace() }) { "Invalid hostname or address: $text" }
                config.ports.size.toLong() * config.schemes.size
            }
        }
    }

    fun units(config: ScanConfig, startIndex: Long = 0): Sequence<ScanUnit> = sequence {
        var index = 0L
        config.sources.asSequence().flatMap(::expandSource).forEach { target ->
            val schemes = target.scheme?.let(::listOf) ?: config.schemes
            schemes.forEach { scheme ->
                val defaultPort = if (scheme in setOf("https", "wss")) 443 else 80
                val ports = if (target.scheme != null) listOf(target.port ?: defaultPort) else config.ports
                ports.forEach { port ->
                    if (index >= startIndex) yield(ScanUnit(index, target.display, target.host, config.hostHeader.ifBlank { target.host }, scheme, port, if (target.scheme != null) target.path else config.path))
                    index++
                }
            }
        }
    }
}
