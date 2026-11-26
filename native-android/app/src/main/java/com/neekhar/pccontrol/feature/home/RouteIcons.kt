package com.neekhar.pccontrol.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps server route icon names (MaterialCommunityIcons slugs from `/pc/api/routes`)
 * to monochrome filled Material Icons vector drawables.
 */
object RouteIcons {
    fun forName(name: String?): ImageVector = when (name?.lowercase()?.removeSuffix("-outline")) {
        "movie", "movie-open", "movie-search", "filmstrip" -> Icons.Filled.Movie
        "television", "television-play", "tv", "jellyfin" -> Icons.Filled.LiveTv
        "play", "play-circle", "video" -> Icons.Filled.PlayCircle
        "robot", "robot-excited", "brain", "agent" -> Icons.Filled.SmartToy
        "download", "tray-arrow-down" -> Icons.Filled.Download
        "harddisk", "database", "nas", "sd" -> Icons.Filled.Storage
        "cctv", "camera", "webcam", "video-box" -> Icons.Filled.Videocam
        "music", "music-note" -> Icons.Filled.MusicNote
        "console", "terminal", "bash" -> Icons.Filled.Terminal
        "code-tags", "code-braces", "git" -> Icons.Filled.Code
        "folder", "folder-network" -> Icons.Filled.Folder
        "server", "server-network" -> Icons.Filled.Dns
        "speedometer", "gauge", "chart-line", "pulse" -> Icons.Filled.Speed
        "chip", "memory", "cpu-64-bit" -> Icons.Filled.Memory
        "cloud", "google-drive" -> Icons.Filled.CloudQueue
        "cog", "tune", "settings" -> Icons.Filled.Settings
        "shield-key", "vpn", "key" -> Icons.Filled.VpnKey
        else -> Icons.Filled.Language
    }
}
