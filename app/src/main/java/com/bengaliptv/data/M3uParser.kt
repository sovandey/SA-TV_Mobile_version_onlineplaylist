package com.bengaliptv.data

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String, source: PlaylistSource): List<Channel> {
        val lines = text.removePrefix("\uFEFF").lines()
        val result = mutableListOf<Channel>()
        var pending: Map<String, String> = emptyMap()
        var pendingName: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.indexOf(',')
                val meta = if (comma >= 0) line.substring(0, comma) else line
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                pending = attr.findAll(meta).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            } else if (line.startsWith("#")) {
                continue
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                val name = pending["tvg-name"]?.takeIf { it.isNotBlank() }
                    ?: pendingName?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/').take(60)
                result += Channel(
                    id = pending["tvg-id"] ?: pending["channel-id"],
                    name = name,
                    url = line,
                    logo = pending["tvg-logo"],
                    group = pending["group-title"],
                    country = pending["tvg-country"],
                    language = pending["tvg-language"],
                    sourcePriority = source.priority,
                    sourceName = source.name
                )
                pending = emptyMap()
                pendingName = null
            }
        }
        return result
    }
}
