package com.noway.edge.scan

object CdnDetector {
    private val cnameSignatures = linkedMapOf(
        "Cloudflare" to listOf("cloudflare.net", "cloudflare.com"),
        "Akamai" to listOf("akamai.net", "akamaiedge.net", "akamaized.net", "edgekey.net", "edgesuite.net"),
        "Amazon CloudFront" to listOf("cloudfront.net"),
        "Fastly" to listOf("fastly.net", "fastlylb.net"),
        "Azure Front Door/CDN" to listOf("azurefd.net", "azureedge.net", "trafficmanager.net"),
        "Google Cloud CDN" to listOf("googleusercontent.com", "googlehosted.com", "google.com"),
        "Bunny CDN" to listOf("b-cdn.net"), "KeyCDN" to listOf("kxcdn.com"),
        "Gcore" to listOf("gcorelabs.net", "gcdn.co"), "Imperva" to listOf("incapdns.net"),
        "Sucuri" to listOf("cloudproxy.net"), "QUIC.cloud" to listOf("quic.cloud"),
        "StackPath" to listOf("stackpathdns.com", "stackpathcdn.com"),
        "CDN77" to listOf("cdn77.org", "cdn77.com"), "Vercel" to listOf("vercel-dns.com", "vercel.app"),
        "Netlify" to listOf("netlify.global", "netlify.app"),
        "GitHub Pages" to listOf("github.io", "github.map.fastly.net"),
    )
    private val headerSignatures = linkedMapOf(
        "Cloudflare" to listOf("cf-ray" to null, "cf-cache-status" to null, "server" to "cloudflare"),
        "Akamai" to listOf("akamai-grn" to null, "x-akamai" to null, "server" to "akamaighost"),
        "Amazon CloudFront" to listOf("x-amz-cf-id" to null, "x-amz-cf-pop" to null),
        "Fastly" to listOf("x-served-by" to null, "x-cache-hits" to null, "via" to "varnish"),
        "Azure Front Door/CDN" to listOf("x-azure-ref" to null, "x-msedge-ref" to null),
        "Google Cloud CDN" to listOf("via" to "google", "server" to "gws"),
        "Bunny CDN" to listOf("server" to "bunnycdn", "cdn-pullzone" to null),
        "Gcore" to listOf("server" to "gcore", "x-gcdn-cache" to null),
        "Imperva" to listOf("x-iinfo" to null, "x-cdn" to "imperva"),
        "Sucuri" to listOf("x-sucuri-id" to null, "x-sucuri-cache" to null),
        "StackPath" to listOf("x-hw" to null, "server" to "stackpath"),
        "QUIC.cloud" to listOf("x-qc-cache" to null, "server" to "quic.cloud"),
        "CDN77" to listOf("server" to "cdn77", "x-77-nzt" to null),
        "Vercel" to listOf("x-vercel-id" to null, "server" to "vercel"),
        "Netlify" to listOf("x-nf-request-id" to null, "server" to "netlify"),
    )

    private val ipSignatures = linkedMapOf(
        "Cloudflare" to listOf(
            "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "104.16.0.0/13",
            "104.24.0.0/14", "108.162.192.0/18", "131.0.72.0/22", "141.101.64.0/18",
            "162.158.0.0/15", "172.64.0.0/13", "173.245.48.0/20", "188.114.96.0/20",
            "190.93.240.0/20", "197.234.240.0/22", "198.41.128.0/17",
        ),
        "Fastly" to listOf(
            "23.235.32.0/20", "43.249.72.0/22", "103.244.50.0/24", "103.245.222.0/23",
            "103.245.224.0/24", "104.156.80.0/20", "140.248.64.0/18", "140.248.128.0/17",
            "146.75.0.0/17", "151.101.0.0/16", "157.52.64.0/18", "167.82.0.0/17",
            "167.82.128.0/20", "167.82.160.0/20", "167.82.224.0/20", "172.111.64.0/18",
            "185.31.16.0/22", "199.27.72.0/21", "199.232.0.0/16",
        ),
    )

    private fun ipv4(value: String): Long? {
        val parts = value.substringBefore('%').split('.')
        if (parts.size != 4) return null
        return parts.fold(0L) { result, part ->
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            (result shl 8) or octet.toLong()
        }
    }

    private fun inCidr(value: String, cidr: String): Boolean {
        val address = ipv4(value) ?: return false
        val network = ipv4(cidr.substringBefore('/')) ?: return false
        val prefix = cidr.substringAfter('/').toInt()
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xffffffffL
        return (address and mask) == (network and mask)
    }

    fun detect(cnames: List<String>, headers: Map<String, String>, ips: List<String>, tls: String): CdnMatch {
        val scores = mutableMapOf<String, Int>(); val evidence = mutableMapOf<String, MutableList<String>>()
        fun add(provider: String, points: Int, item: String) { scores[provider] = (scores[provider] ?: 0) + points; evidence.getOrPut(provider) { mutableListOf() }.run { if (item !in this) add(item) } }
        val normalizedCnames = cnames.map(String::lowercase)
        cnameSignatures.forEach { (provider, suffixes) -> normalizedCnames.firstOrNull { cname -> suffixes.any { cname == it || cname.endsWith(".$it") } }?.let { add(provider, 70, "CNAME $it") } }
        val normalizedHeaders = headers.mapKeys { it.key.lowercase() }.mapValues { it.value.lowercase() }
        headerSignatures.forEach { (provider, signatures) -> signatures.forEach { (name, needle) -> normalizedHeaders.entries.firstOrNull { (actual, value) -> (actual == name || (name.endsWith('-') && actual.startsWith(name))) && (needle == null || needle in value) }?.let { add(provider, 45, "Header ${it.key}") } } }
        ips.forEach { ip ->
            ipSignatures.forEach { (provider, ranges) ->
                if (ranges.any { inCidr(ip, it) }) add(provider, 55, "Known edge range $ip")
            }
        }
        val lowerTls = tls.lowercase(); scores.keys.forEach { provider -> if (provider.lowercase().split(' ').any { it.length > 4 && it in lowerTls }) add(provider, 20, "TLS identity") }
        val provider = scores.maxByOrNull { it.value }?.key ?: return CdnMatch()
        return CdnMatch(provider, minOf(100, scores.getValue(provider)), evidence[provider].orEmpty())
    }
}
