package com.bengaliptv.data

data class Channel(
    val id: String?,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val country: String? = null,
    val language: String? = null,
    val sourcePriority: Int = 0,
    val sourceName: String = "",
    val backupUrls: List<String> = emptyList(),
    val lastVerifiedAt: Long? = null
)

data class PlaylistSource(val name: String, val url: String, val priority: Int)

object Sources {
    val all = listOf(
        PlaylistSource("Primary BDIX", "https://raw.githubusercontent.com/saeidrahmanbd/BDIX-IPTV/main/IPTV-Playlist.m3u", 0),
        PlaylistSource("Backup IPTV-org", "https://iptv-org.github.io/iptv/index.m3u", 1),
        PlaylistSource("Backup India Active", "https://raw.githubusercontent.com/gnaidu05/iptv/main/playlists/india-active.m3u", 2)
    )
}
