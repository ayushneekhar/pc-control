package com.neekhar.pccontrol.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps server route icon names (MaterialCommunityIcons slugs from `/pc/api/routes`)
 * to clean rounded Material Icons vector drawables.
 */
object RouteIcons {
    fun forName(name: String?): ImageVector = when (name?.lowercase()?.removeSuffix("-outline")) {
        "movie", "movie-open", "movie-search", "filmstrip" -> Icons.Rounded.Movie
        "television", "television-play", "tv", "jellyfin" -> Icons.Rounded.SmartDisplay
        "play", "play-circle", "video" -> Icons.Rounded.PlayCircle
        "robot", "robot-excited", "brain", "agent" -> Icons.Rounded.SmartToy
        "download", "tray-arrow-down" -> Icons.Rounded.Download
        "harddisk", "database", "nas", "sd" -> Icons.Rounded.Storage
        "cctv", "camera", "webcam", "video-box" -> Icons.Rounded.Videocam
        "music", "music-note" -> Icons.Rounded.MusicNote
        "console", "terminal", "bash" -> Icons.Rounded.Terminal
        "code-tags", "code-braces", "git" -> Icons.Rounded.Code
        "folder", "folder-network" -> Icons.Rounded.Folder
        "server", "server-network" -> Icons.Rounded.Dns
        "speedometer", "gauge", "chart-line", "pulse" -> Icons.Rounded.Speed
        "chip", "memory", "cpu-64-bit" -> Icons.Rounded.Memory
        "cloud", "google-drive" -> Icons.Rounded.Cloud
        "cog", "tune", "settings" -> Icons.Rounded.Settings
        "shield-key", "vpn", "key" -> Icons.Rounded.VpnKey
        else -> Icons.Rounded.Language
    }
}
