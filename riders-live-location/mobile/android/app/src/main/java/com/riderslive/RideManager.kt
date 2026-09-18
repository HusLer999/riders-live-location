package com.riderslive

import com.riderslive.security.CryptoManager

/**
 * Kotlin port of python/ride_manager.py. Same state machine, same rules —
 * kept as a direct behavioral mirror of the reference implementation so
 * the two never drift apart. See that file's docstring for the full
 * rationale of each rule.
 */
enum class RideState { CREATED, WAITING, ACTIVE, PAUSED, ENDED }
enum class Role { PRIMARY, RIDER }

open class RideError(message: String) : Exception(message)
class UnauthorizedError(message: String) : RideError(message)

private val VALID_TRANSITIONS: Map<RideState, Set<RideState>> = mapOf(
    RideState.CREATED to setOf(RideState.WAITING),
    RideState.WAITING to setOf(RideState.ACTIVE, RideState.ENDED),
    RideState.ACTIVE to setOf(RideState.PAUSED, RideState.ENDED),
    RideState.PAUSED to setOf(RideState.ACTIVE, RideState.ENDED),
    RideState.ENDED to emptySet(),
)

data class Participant(
    val riderId: String,
    var displayName: String,
    val role: Role,
    var keyMaterial: CryptoManager.KeyPairRaw,
    var sessionKey: ByteArray? = null,
    var revoked: Boolean = false,
    var shareSpeed: Boolean = true,
    var shareHeading: Boolean = true,
)

data class Destination(
    val latitude: Double,
    val longitude: Double,
    val name: String?,
    val setByRiderId: String,
)

class Ride(val rideId: String, val code: String) {
    var state: RideState = RideState.CREATED
        private set
    val participants = LinkedHashMap<String, Participant>()
    var destination: Destination? = null
        private set
    val maxRecommendedParticipants = 8

    private fun transition(newState: RideState) {
        if (newState !in (VALID_TRANSITIONS[state] ?: emptySet())) {
            throw RideError("Cannot transition ride from $state to $newState")
        }
        state = newState
    }

    fun openForJoining() = transition(RideState.WAITING)
    fun start() = transition(RideState.ACTIVE)
    fun pause() = transition(RideState.PAUSED)
    fun resume() = transition(RideState.ACTIVE)

    fun end(crypto: CryptoManager) {
        transition(RideState.ENDED)
        participants.values.forEach { it.sessionKey?.let(crypto::wipe) }
        participants.clear()
    }

    fun createAsPrimary(displayName: String, crypto: CryptoManager): Participant {
        val riderId = randomRiderId()
        val participant = Participant(riderId, displayName, Role.PRIMARY, crypto.generateEphemeralKeyPair())
        participants[riderId] = participant
        return participant
    }

    fun addParticipant(displayName: String, crypto: CryptoManager, role: Role = Role.RIDER): Participant {
        if (state == RideState.ENDED) throw RideError("Cannot join a ride that has ended")
        val riderId = randomRiderId()
        val participant = Participant(riderId, displayName, role, crypto.generateEphemeralKeyPair())
        participants[riderId] = participant
        return participant
    }

    private fun requirePrimary(actingRiderId: String): Participant {
        val actor = participants[actingRiderId]
        if (actor == null || actor.revoked) throw UnauthorizedError("Unknown or revoked rider")
        if (actor.role != Role.PRIMARY) throw UnauthorizedError("Only the primary rider may perform this action")
        return actor
    }

    fun removeParticipant(actingRiderId: String, targetRiderId: String, crypto: CryptoManager, peerPublicKeys: Map<String, ByteArray>) {
        requirePrimary(actingRiderId)
        val target = participants[targetRiderId] ?: throw RideError("No such participant")
        target.revoked = true
        participants.remove(targetRiderId)

        // Rotate remaining participants' session keys (forward secrecy
        // across revocation) — mirrors security.rotate_session_key.
        for ((riderId, participant) in participants) {
            val peerPublic = peerPublicKeys[riderId] ?: continue
            val fresh = crypto.generateEphemeralKeyPair()
            participant.sessionKey?.let(crypto::wipe)
            participant.sessionKey = crypto.deriveSessionKey(fresh.privateKey, peerPublic, rideId, code)
            participant.keyMaterial = fresh
        }
    }

    fun setDestination(actingRiderId: String, latitude: Double, longitude: Double, name: String?): Destination {
        val actor = requirePrimary(actingRiderId)
        val dest = Destination(latitude, longitude, name, actor.riderId)
        destination = dest
        return dest
    }

    companion object {
        private val secureRandom = java.security.SecureRandom()

        private fun randomRiderId(): String {
            val alphabet = "0123456789ABCDEF"
            val suffix = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
            return "RIDER-$suffix"
        }
    }
}
