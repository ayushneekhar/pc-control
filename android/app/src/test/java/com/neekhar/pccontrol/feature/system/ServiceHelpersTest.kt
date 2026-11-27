package com.neekhar.pccontrol.feature.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHelpersTest {
    private fun unit(
        name: String,
        active: String = "active",
        timer: Boolean = false,
    ): ServiceUnit = ServiceUnit(
        unit = name,
        description = "$name description",
        active = active,
        sub = if (active == "active") "running" else "dead",
        enabled = "enabled",
        timer = timer,
    )

    @Test
    fun `group classifies media cams agents and general services`() {
        assertEquals(ServiceGroup.MEDIA, ServiceHelpers.group(unit("jellyfin.service")))
        assertEquals(ServiceGroup.MEDIA, ServiceHelpers.group(unit("qbittorrent.service")))
        assertEquals(ServiceGroup.MEDIA, ServiceHelpers.group(unit("radarr.service")))
        assertEquals(ServiceGroup.MEDIA, ServiceHelpers.group(unit("sonarr.service")))
        assertEquals(ServiceGroup.MEDIA, ServiceHelpers.group(unit("import-watch.service")))

        assertEquals(ServiceGroup.CAMS, ServiceHelpers.group(unit("catcam.service")))
        assertEquals(ServiceGroup.CAMS, ServiceHelpers.group(unit("catdetect.service")))
        assertEquals(ServiceGroup.CAMS, ServiceHelpers.group(unit("cam-ptz.service")))
        assertEquals(ServiceGroup.CAMS, ServiceHelpers.group(unit("camrec.service")))

        assertEquals(ServiceGroup.AGENTS, ServiceHelpers.group(unit("qwen-agent.service")))
        assertEquals(ServiceGroup.AGENTS, ServiceHelpers.group(unit("ollama.service")))
        assertEquals(ServiceGroup.AGENTS, ServiceHelpers.group(unit("agentview.service")))

        assertEquals(ServiceGroup.ALL, ServiceHelpers.group(unit("pc-api.service")))
        assertEquals(ServiceGroup.ALL, ServiceHelpers.group(unit("vitals.service")))
    }

    @Test
    fun `sortAndFilter orders problems first then alphabetically`() {
        val services = listOf(
            unit("sonarr.service", active = "active"),
            unit("radarr.service", active = "inactive"),
            unit("jellyfin.service", active = "failed"),
            unit("bazarr.service", active = "activating"),
            unit("qbittorrent.service", active = "active"),
        )
        val sorted = ServiceHelpers.sortAndFilter(services, ServiceGroup.ALL)
        assertEquals(
            listOf(
                "jellyfin.service",   // BAD
                "bazarr.service",     // WARN
                "qbittorrent.service",// OK (alpha before sonarr)
                "sonarr.service",     // OK
                "radarr.service",     // DIM
            ),
            sorted.map { it.unit },
        )
    }

    @Test
    fun `sortAndFilter respects group filter and catCamEnabled fallback`() {
        val services = listOf(
            unit("jellyfin.service"),
            unit("catcam.service"),
            unit("ollama.service"),
            unit("pc-api.service"),
        )
        assertEquals(listOf("jellyfin.service"), ServiceHelpers.sortAndFilter(services, ServiceGroup.MEDIA).map { it.unit })
        assertEquals(listOf("catcam.service"), ServiceHelpers.sortAndFilter(services, ServiceGroup.CAMS, catCamEnabled = true).map { it.unit })
        assertEquals(4, ServiceHelpers.sortAndFilter(services, ServiceGroup.CAMS, catCamEnabled = false).size)
        assertEquals(listOf("ollama.service"), ServiceHelpers.sortAndFilter(services, ServiceGroup.AGENTS).map { it.unit })
    }

    @Test
    fun `friendlyName maps known units and title-cases unknown units`() {
        assertEquals("qBittorrent", ServiceHelpers.friendlyName("qbittorrent.service"))
        assertEquals("Cat cameras", ServiceHelpers.friendlyName("catcam.service"))
        assertEquals("Cam PTZ", ServiceHelpers.friendlyName("cam-ptz.service"))
        assertEquals("Pc Api", ServiceHelpers.friendlyName("pc-api.service"))
        assertEquals("Proton Wg Watchdog", ServiceHelpers.friendlyName("proton_wg-watchdog.service"))
    }

    @Test
    fun `statusLabel and protected unit detection`() {
        assertEquals("timer", ServiceHelpers.statusLabel(unit("backup.service", active = "inactive", timer = true)))
        assertEquals("active", ServiceHelpers.statusLabel(unit("backup.service", active = "active", timer = true)))
        assertEquals("failed", ServiceHelpers.statusLabel(unit("x.service", active = "failed")))
        assertEquals("unknown", ServiceHelpers.statusLabel(unit("x.service", active = "")))

        assertTrue(ServiceHelpers.isProtectedUnit("pc-api.service"))
        assertFalse(ServiceHelpers.isProtectedUnit("jellyfin.service"))
    }

    @Test
    fun `filterAndSortProcesses searches by name cmd and pid and sorts accurately`() {
        val procs = listOf(
            ProcessInfo(pid = 100, name = "python3", user = "ayushlive", cpu = 12.5, rss = 200L, cmd = "python3 pcapi.py"),
            ProcessInfo(pid = 200, name = "jellyfin", user = "jellyfin", cpu = 48.0, rss = 900L, cmd = "/usr/bin/jellyfin"),
            ProcessInfo(pid = 300, name = "ffmpeg", user = "ayushlive", cpu = 5.0, rss = 500L, cmd = "ffmpeg -i rtsp://cam"),
        )
        val (byCpu, totalCpu) = ServiceHelpers.filterAndSortProcesses(procs, "", ProcessSortKey.CPU, descending = true)
        assertEquals(3, totalCpu)
        assertEquals(listOf(200, 100, 300), byCpu.map { it.pid })

        val (byPid, pidCount) = ServiceHelpers.filterAndSortProcesses(procs, "300", ProcessSortKey.CPU, descending = true)
        assertEquals(1, pidCount)
        assertEquals(listOf("ffmpeg"), byPid.map { it.name })

        val (byCmd, cmdCount) = ServiceHelpers.filterAndSortProcesses(procs, "pcapi", ProcessSortKey.NAME, descending = false)
        assertEquals(1, cmdCount)
        assertEquals(listOf(100), byCmd.map { it.pid })
    }

    @Test
    fun `sortedTemperatures places cpu first case-insensitively and omits nulls`() {
        val temps = mapOf(
            "nvme" to 42.0,
            "gpu" to 55.0,
            "cpu" to 61.5,
            "broken" to null,
        )
        assertEquals(
            listOf("cpu" to 61.5, "gpu" to 55.0, "nvme" to 42.0),
            ServiceHelpers.sortedTemperatures(temps),
        )

        val upperTemps = mapOf(
            "GPU" to 59.0,
            "CPU" to 64.0,
            "NVMe" to 41.0,
        )
        assertEquals(
            listOf("CPU" to 64.0, "GPU" to 59.0, "NVMe" to 41.0),
            ServiceHelpers.sortedTemperatures(upperTemps),
        )
    }

    @Test
    fun `primaryCpuTemp picks CPU sensor case-insensitively over hotter other sensors`() {
        val upperCpu = VitalsResponse(temps = mapOf("CPU" to 64.0, "acpi" to 72.0, "GPU" to 59.0))
        assertEquals(64.0, ServiceHelpers.primaryCpuTemp(upperCpu) ?: -1.0, 0.001)

        val pkgCpu = VitalsResponse(temps = mapOf("Package id 0" to 62.5, "nvme" to 70.0))
        assertEquals(62.5, ServiceHelpers.primaryCpuTemp(pkgCpu) ?: -1.0, 0.001)

        val fallback = VitalsResponse(temps = mapOf("sensor_a" to 40.0, "sensor_b" to 58.0))
        assertEquals(58.0, ServiceHelpers.primaryCpuTemp(fallback) ?: -1.0, 0.001)
    }

    @Test
    fun `servicesSummaryCopy avoids All running contradiction when inactive units exist`() {
        val withInactive = ServiceHelpers.servicesSummaryCopy(
            runningCount = 20,
            counts = ServiceCounts(running = 20, failed = 0, inactive = 9),
            problemCount = 0,
        )
        assertEquals("20 running · 9 inactive", withInactive.headline)
        assertEquals("0 failed · all healthy", withInactive.detail)

        val allActive = ServiceHelpers.servicesSummaryCopy(
            runningCount = 20,
            counts = ServiceCounts(running = 20, failed = 0, inactive = 0),
            problemCount = 0,
        )
        assertEquals("All 20 running", allActive.headline)
        assertEquals("0 failed · all healthy", allActive.detail)

        val withProblems = ServiceHelpers.servicesSummaryCopy(
            runningCount = 19,
            counts = ServiceCounts(running = 19, failed = 1, inactive = 9),
            problemCount = 1,
        )
        assertEquals("1 problem", withProblems.headline)
        assertEquals("19 running · 1 failed · 9 inactive", withProblems.detail)
    }

    @Test
    fun `vitalsProgressFraction keeps low positive values visible and zeroes non-positive`() {
        assertEquals(0f, vitalsProgressFraction(0.0), 0.0001f)
        assertEquals(0f, vitalsProgressFraction(-5.0), 0.0001f)
        assertEquals(0f, vitalsProgressFraction(Double.NaN), 0.0001f)
        assertEquals(0.04f, vitalsProgressFraction(0.5), 0.0001f)
        assertEquals(0.04f, vitalsProgressFraction(2.0), 0.0001f)
        assertEquals(0.80f, vitalsProgressFraction(80.0), 0.0001f)
        assertEquals(1.0f, vitalsProgressFraction(120.0), 0.0001f)
    }

    @Test
    fun `computeCpuSparklineMax auto-scales idle and peak telemetry`() {
        assertEquals(20.0, computeCpuSparklineMax(emptyList()), 0.001)
        assertEquals(20.0, computeCpuSparklineMax(listOf(3.0, 5.2, 4.8)), 0.001)
        assertEquals(20.0, computeCpuSparklineMax(listOf(null, Double.NaN, -5.0, 10.0)), 0.001)
        assertEquals(60.0, computeCpuSparklineMax(listOf(12.0, 50.0, 22.0)), 0.001)

        // Only considers the last maxPoints samples
        val history = listOf(95.0) + List(60) { 6.0 }
        assertEquals(20.0, computeCpuSparklineMax(history, maxPoints = 60), 0.001)
    }
}
