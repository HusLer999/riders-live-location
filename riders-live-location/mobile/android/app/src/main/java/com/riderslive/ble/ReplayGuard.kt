package com.riderslive.ble

/**
 * Direct port of protocol.ReplayGuard (python/protocol.py). Kept
 * behaviorally identical on purpose — the Python module is the
 * reference spec and this is the on-device implementation.
 */
class ReplayGuard(
    private val freshnessWindowSeconds: Long = 30,
    private val dedupTtlSeconds: Long = 60,
) {
    private data class SeenEntry(val seenAtEpochSeconds: Long)

    private val seenPacketIds = LinkedHashMap<String, SeenEntry>()
    private val lastSequenceByRider = HashMap<String, Long>()

    data class Verdict(val accept: Boolean, val reason: String)

    @Synchronized
    fun shouldAccept(
        packetId: String,
        riderId: String,
        sequenceNumber: Long,
        timestampEpochSeconds: Double,
        nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): Verdict {
        evictExpired(nowEpochSeconds.toLong())

        if (nowEpochSeconds - timestampEpochSeconds > freshnessWindowSeconds) {
            return Verdict(false, "stale packet (outside freshness window)")
        }
        if (timestampEpochSeconds - nowEpochSeconds > 5) {
            return Verdict(false, "packet timestamped in the future")
        }
        if (seenPacketIds.containsKey(packetId)) {
            return Verdict(false, "duplicate packet_id (replay or re-relay)")
        }
        val lastSeq = lastSequenceByRider[riderId] ?: -1L
        if (sequenceNumber <= lastSeq) {
            return Verdict(false, "sequence_number did not advance (replay)")
        }
        return Verdict(true, "ok")
    }

    @Synchronized
    fun recordAccepted(packetId: String, riderId: String, sequenceNumber: Long, nowEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        seenPacketIds[packetId] = SeenEntry(nowEpochSeconds)
        lastSequenceByRider[riderId] = sequenceNumber
    }

    @Synchronized
    fun hasForwarded(packetId: String): Boolean = seenPacketIds.containsKey(packetId)

    private fun evictExpired(nowEpochSeconds: Long) {
        val iterator = seenPacketIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowEpochSeconds - entry.value.seenAtEpochSeconds > dedupTtlSeconds) {
                iterator.remove()
            }
        }
    }
}
