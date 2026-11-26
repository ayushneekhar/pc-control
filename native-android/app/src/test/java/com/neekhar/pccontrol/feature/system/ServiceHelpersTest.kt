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
    fun `sortedTemperatures places cpu first and omits nulls`() {
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
    }
}
