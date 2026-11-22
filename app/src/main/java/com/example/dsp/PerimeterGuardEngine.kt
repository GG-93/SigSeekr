package com.example.dsp

/**
 * Virtual Perimeter Guard (3m–100m bubble) with 3-packet hysteresis.
 *
 * Prevents RSSI jitter false alarms by requiring [HYSTERESIS_PACKETS] (3)
 * consecutive observations inside/outside [radiusMeters] before firing an
 * ENTER or LEAVE crossing event.
 */
class PerimeterGuardEngine {

    companion object {
        const val MIN_RADIUS_METERS = 3.0f
        const val MAX_RADIUS_METERS = 100.0f
        const val HYSTERESIS_PACKETS = 3
    }

    private enum class PresenceState {
        UNKNOWN,
        INSIDE,
        OUTSIDE
    }

    private class EmitterTrackerState(
        var confirmedState: PresenceState = PresenceState.UNKNOWN,
        var candidateState: PresenceState = PresenceState.UNKNOWN,
        var consecutiveCount: Int = 0
    )

    private val lock = Any()
    private val emitterStates = HashMap<String, EmitterTrackerState>()

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var radiusMeters: Float = 15.0f

    @Volatile
    var triggerMode: PerimeterTriggerMode = PerimeterTriggerMode.BOTH

    @Volatile
    var scope: PerimeterScope = PerimeterScope.ALL_UNKNOWN

    fun reset() {
        synchronized(lock) {
            emitterStates.clear()
        }
    }

    fun evaluatePacket(
        emitterKey: String,
        emitterName: String,
        distanceMeters: Double,
        rssiDbm: Int,
        isWatchlistMatch: Boolean,
        isLockedTarget: Boolean,
        timestampMs: Long
    ): PerimeterEventRecord? {
        if (!enabled || !distanceMeters.isFinite()) return null

        val inScope = when (scope) {
            PerimeterScope.ALL_UNKNOWN -> true
            PerimeterScope.WATCHLIST_ONLY -> isWatchlistMatch
            PerimeterScope.LOCKED_TARGET -> isLockedTarget
        }
        if (!inScope) return null

        val threshold = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS).toDouble()
        val observed = if (distanceMeters <= threshold) PresenceState.INSIDE else PresenceState.OUTSIDE

        synchronized(lock) {
            val state = emitterStates.getOrPut(emitterKey) { EmitterTrackerState() }

            if (state.confirmedState == PresenceState.UNKNOWN) {
                if (observed == state.candidateState) {
                    state.consecutiveCount++
                } else {
                    state.candidateState = observed
                    state.consecutiveCount = 1
                }
                if (state.consecutiveCount >= HYSTERESIS_PACKETS) {
                    state.confirmedState = observed
                    state.consecutiveCount = 0
                    if (observed == PresenceState.INSIDE &&
                        (triggerMode == PerimeterTriggerMode.ENTER || triggerMode == PerimeterTriggerMode.BOTH)
                    ) {
                        return PerimeterEventRecord(
                            timestampEpochMs = timestampMs,
                            emitterKey = emitterKey,
                            emitterName = emitterName,
                            eventType = "ENTER",
                            distanceMeters = distanceMeters,
                            rssiDbm = rssiDbm
                        )
                    }
                }
                return null
            }

            if (observed == state.confirmedState) {
                state.candidateState = observed
                state.consecutiveCount = 0
                return null
            }

            if (observed == state.candidateState) {
                state.consecutiveCount++
            } else {
                state.candidateState = observed
                state.consecutiveCount = 1
            }

            if (state.consecutiveCount >= HYSTERESIS_PACKETS) {
                val previous = state.confirmedState
                state.confirmedState = observed
                state.consecutiveCount = 0

                val firedEnter = previous == PresenceState.OUTSIDE &&
                    observed == PresenceState.INSIDE &&
                    (triggerMode == PerimeterTriggerMode.ENTER || triggerMode == PerimeterTriggerMode.BOTH)
                val firedLeave = previous == PresenceState.INSIDE &&
                    observed == PresenceState.OUTSIDE &&
                    (triggerMode == PerimeterTriggerMode.LEAVE || triggerMode == PerimeterTriggerMode.BOTH)

                if (firedEnter || firedLeave) {
                    return PerimeterEventRecord(
                        timestampEpochMs = timestampMs,
                        emitterKey = emitterKey,
                        emitterName = emitterName,
                        eventType = if (firedEnter) "ENTER" else "LEAVE",
                        distanceMeters = distanceMeters,
                        rssiDbm = rssiDbm
                    )
                }
            }
            return null
        }
    }
}
