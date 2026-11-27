package com.bengaliptv.data

import java.text.Normalizer
import java.util.Locale

object ChannelMatcher {
    private fun norm(value: String?): String =
        Normalizer.normalize(value.orEmpty().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\\\p{Mn}+"), "")
            .replace(Regex("\\\\b(hd|fhd|uhd|sd|1080p|720p|576p|480p)\\\\b"), "")
            .replace(Regex("[^a-z0-9]+"), "")

    fun sameChannel(a: Channel, b: Channel): Boolean {
        if (!a.id.isNullOrBlank() && !b.id.isNullOrBlank() && a.id.equals(b.id, true)) return true
        val an = norm(a.name)
        val bn = norm(b.name)
        if (an.length < 4 || bn.length < 4) return false
        if (an == bn) return true
        val containment = an.length >= 6 && bn.length >= 6 && (an.contains(bn) || bn.contains(an))
        return containment && (a.country.isNullOrBlank() || b.country.isNullOrBlank() ||
                a.country.equals(b.country, true))
    }

    fun backupsFor(primary: Channel, all: List<Channel>): List<Channel> =
        all.filter { it.url != primary.url && sameChannel(primary, it) }
            .distinctBy { it.url }
            .sortedBy { it.sourcePriority }
}
