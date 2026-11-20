package com.noway.edge.scan

import com.noway.edge.normalizeImportedTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerCoreTest {
    @Test
    fun portsAndCidrPlanAreDeterministic() {
        assertEquals(listOf(80, 443, 8000, 8001, 8002), TargetParser.parsePorts("443,80,8000-8002"))
        val config = ScanConfig(
            sources = listOf("192.0.2.0/30"),
            ports = listOf(80, 443),
            schemes = listOf("http", "https"),
            authorized = true,
        )
        assertEquals(16L, TargetParser.estimate(config))
        val units = TargetParser.units(config).toList()
        assertEquals("192.0.2.0", units.first().connectHost)
        assertEquals("192.0.2.3", units.last().connectHost)
    }

    @Test
    fun scanPlanCoversOnlyTheGivenRange() {
        val cidr = ScanConfig(sources = listOf("104.16.0.0/12"), ports = listOf(80, 443), schemes = listOf("http", "https"), authorized = true)
        val cidrPlan = TargetParser.plan(cidr)
        assertEquals("104.16.0.0", cidrPlan.first)
        assertEquals("104.31.255.255", cidrPlan.last)
        assertEquals(4_194_304L, cidrPlan.total)

        val range = ScanConfig(sources = listOf("103.21.244.0-103.21.247.255"), ports = listOf(80, 443), schemes = listOf("http", "https"), authorized = true)
        val rangePlan = TargetParser.plan(range)
        assertEquals("103.21.244.0", rangePlan.first)
        assertEquals("103.21.247.255", rangePlan.last)
        assertEquals(4_096L, rangePlan.total)

        // The plan's first/last targets match the units the scanner actually walks.
        val units = TargetParser.units(range).toList()
        assertEquals(rangePlan.first, units.first().connectHost)
        assertEquals(rangePlan.last, units.last().connectHost)
    }

    @Test
    fun cdnDetectionUsesIndependentEvidence() {
        val cloudflare = CdnDetector.detect(
            cnames = emptyList(),
            headers = mapOf("CF-Ray" to "test-LHR", "Server" to "cloudflare"),
            ips = emptyList(),
            tls = "",
        )
        assertEquals("Cloudflare", cloudflare.provider)
        assertEquals(90, cloudflare.confidence)

        val fastly = CdnDetector.detect(emptyList(), emptyMap(), listOf("151.101.1.69"), "")
        assertEquals("Fastly", fastly.provider)
        assertTrue(fastly.evidence.first().startsWith("Known edge range"))
    }

    @Test
    fun requestMetadataRejectsLineInjection() {
        val config = ScanConfig(
            sources = listOf("example.com"),
            path = "/ok\r\nInjected: yes",
            authorized = true,
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
        assertThrows(IllegalArgumentException::class.java) {
            TargetParser.estimate(config.copy(sources = listOf("192.0.2.9-192.0.2.1"), path = "/"))
        }
    }

    @Test
    fun csvImportSelectsTheNamedTargetColumn() {
        val csv = "name,target,notes\nEdge one,example.com,primary\nEdge two,\"192.0.2.0/30\",lab"
        assertEquals("example.com\n192.0.2.0/30", normalizeImportedTargets(csv))
    }

    @Test
    fun mobilePerformanceProfilesStayInsideValidatedLimits() {
        val fixedProfiles = MobilePerformanceProfile.entries.filterNot { it.isCustom }
        assertEquals(6, fixedProfiles.size)
        assertTrue(fixedProfiles.all { it.workers!! in 1..MAX_MOBILE_WORKERS })
        assertTrue(fixedProfiles.all { it.rateLimit!! in 0..2_000 })
        assertEquals(32, MobilePerformanceProfile.BALANCED.workers)

        ScanConfig(sources = listOf("example.com"), workers = MAX_MOBILE_WORKERS, authorized = true).validate()
        assertThrows(IllegalArgumentException::class.java) {
            ScanConfig(sources = listOf("example.com"), workers = MAX_MOBILE_WORKERS + 1, authorized = true).validate()
        }
    }

    @Test
    fun highLoadSettingsRequireSecondConfirmation() {
        val normal = ScanConfig(sources = listOf("example.com"), workers = 48, rateLimit = 160, authorized = true)
        assertFalse(normal.requiresHighLoadConfirmation(totalChecks = 1_000_000))
        assertTrue(normal.copy(workers = 128).requiresHighLoadConfirmation(totalChecks = 10))
        assertTrue(normal.copy(rateLimit = 501).requiresHighLoadConfirmation(totalChecks = 10))
        assertTrue(normal.requiresHighLoadConfirmation(totalChecks = 1_000_001))
    }

}
