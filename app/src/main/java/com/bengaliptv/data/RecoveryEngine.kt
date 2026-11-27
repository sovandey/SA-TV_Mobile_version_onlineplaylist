package com.bengaliptv.data

/**
 * Recovery policy:
 * - Never delete the current URL.
 * - Only promote a backup after preflight AND a real player playback-success callback.
 * - Keep previous URLs in history for rollback.
 */
class RecoveryEngine {
    data class Candidate(val channel: Channel, val reason: String)
    data class RecoveryResult(
        val current: Channel,
        val candidate: Candidate?,
        val history: List<String>,
        val message: String
    )

    suspend fun findCandidate(current: Channel, allChannels: List<Channel>): RecoveryResult {
        val candidates = ChannelMatcher.backupsFor(current, allChannels)
        for (candidate in candidates) {
            if (StreamVerifier.preflight(candidate.url)) {
                return RecoveryResult(
                    current = current,
                    candidate = Candidate(candidate, "Matched channel; HLS/network preflight passed"),
                    history = listOf(current.url),
                    message = "Candidate found. Confirm successful Media3 playback before saving."
                )
            }
        }
        return RecoveryResult(current, null, emptyList(), "No verified alternative found; original URL retained.")
    }
}
