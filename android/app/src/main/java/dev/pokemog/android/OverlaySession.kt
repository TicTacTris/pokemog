package dev.pokemog.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class OverlayPhase { OFF, STARTING, ON, STOPPING }

data class OverlaySessionState(val phase: OverlayPhase = OverlayPhase.OFF, val requestId: Long = 0, val error: String? = null, val awaitingPermission: Boolean = false) {
    val buttonText: String get() = when (phase) {
        OverlayPhase.OFF -> "Start overlay"
        OverlayPhase.STARTING -> "Starting..."
        OverlayPhase.ON -> "Stop overlay"
        OverlayPhase.STOPPING -> "Stopping..."
    }
    val description: String get() = when (phase) {
        OverlayPhase.OFF -> "Tap to enable the floating scanner."
        OverlayPhase.STARTING -> if (awaitingPermission) "Complete the Android permission prompt." else "Starting scanner..."
        OverlayPhase.ON -> "Scanner active. Tap to stop."
        OverlayPhase.STOPPING -> "Ending screen capture."
    }
}

/** Process-local state, never persisted: spent capture consent cannot survive process death. */
open class OverlaySessionController {
    private val mutable = MutableStateFlow(OverlaySessionState())
    val state: StateFlow<OverlaySessionState> = mutable.asStateFlow()
    private var nextRequest = 0L

    @Synchronized fun beginStart(): Long? {
        if (mutable.value.phase != OverlayPhase.OFF) return null
        val id = ++nextRequest
        mutable.value = OverlaySessionState(OverlayPhase.STARTING, id, awaitingPermission = true)
        return id
    }
    @Synchronized fun isStarting(id: Long): Boolean = mutable.value.requestId == id && mutable.value.phase == OverlayPhase.STARTING
    @Synchronized fun dispatched(id: Long): Boolean {
        if (!isStarting(id) || !mutable.value.awaitingPermission) return false
        mutable.value = mutable.value.copy(awaitingPermission = false)
        return true
    }
    @Synchronized fun running(id: Long): Boolean {
        if (!isStarting(id) || mutable.value.awaitingPermission) return false
        mutable.value = OverlaySessionState(OverlayPhase.ON, id)
        return true
    }
    @Synchronized fun beginStop(): Long? {
        val current = mutable.value
        if (current.phase != OverlayPhase.ON && current.phase != OverlayPhase.STARTING) return null
        mutable.value = current.copy(phase = OverlayPhase.STOPPING, error = null, awaitingPermission = false)
        return current.requestId
    }
    @Synchronized fun stopped(id: Long, reason: String? = null) {
        // A late callback from an old service must not reset a newer start request.
        if (mutable.value.requestId != id || mutable.value.phase == OverlayPhase.OFF) return
        mutable.value = OverlaySessionState(OverlayPhase.OFF, id, reason)
    }
}

object OverlaySession : OverlaySessionController() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null

    // This outlives Activity/ViewModel recreation while waiting for service startup.
    fun watchStartup(requestId: Long, stopService: () -> Unit) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(12_000)
            if (isStarting(requestId) && !state.value.awaitingPermission) {
                runCatching(stopService)
                stopped(requestId, "Overlay startup timed out. Try again.")
            }
        }
    }
}
