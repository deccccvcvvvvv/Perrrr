package pro.cslauncher.core.launch

import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class LaunchState { IDLE, VALIDATING, PREPARING_RUNTIME, PREPARING_RENDERER, INITIALIZING_GAME, LOADING_GAME, RUNNING, EXITED, FAILED }

data class LaunchStatus(val state: LaunchState, val failedAt: LaunchState? = null, val message: String? = null)

class IllegalLaunchTransition(from: LaunchState, to: LaunchState) : IllegalStateException("Illegal launch transition $from -> $to")

/** Explicit launch state machine. Failures record the stage at which they happened. */
class LaunchStateMachine {
    private val order = listOf(LaunchState.IDLE, LaunchState.VALIDATING, LaunchState.PREPARING_RUNTIME, LaunchState.PREPARING_RENDERER,
        LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME, LaunchState.RUNNING, LaunchState.EXITED)
    private val _status = MutableStateFlow(LaunchStatus(LaunchState.IDLE))
    val status: StateFlow<LaunchStatus> = _status.asStateFlow()
    val state get() = _status.value.state

    @Synchronized fun advance(to: LaunchState, message: String? = null) {
        val from = state
        val ok = when {
            to == LaunchState.FAILED -> from != LaunchState.EXITED && from != LaunchState.FAILED && from != LaunchState.IDLE
            to == LaunchState.EXITED -> from in setOf(LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME, LaunchState.RUNNING)
            to == LaunchState.VALIDATING -> from == LaunchState.IDLE || from == LaunchState.EXITED || from == LaunchState.FAILED
            else -> order.indexOf(to) > order.indexOf(from) && from != LaunchState.EXITED && from != LaunchState.FAILED
        }
        if (!ok) throw IllegalLaunchTransition(from, to)
        _status.value = LaunchStatus(to, message = message)
    }

    /** Updates the status message without changing state (e.g. "Unpacking Java 21…"). */
    @Synchronized fun progress(message: String) { _status.value = _status.value.copy(message = message) }

    @Synchronized fun fail(message: String) {
        val from = state
        if (from == LaunchState.FAILED || from == LaunchState.EXITED || from == LaunchState.IDLE) return
        _status.value = LaunchStatus(LaunchState.FAILED, failedAt = from, message = message)
    }

    @Synchronized fun reset() { _status.value = LaunchStatus(LaunchState.IDLE) }
}

/** Per-profile launch lock preventing duplicate launches. */
object LaunchLocks {
    private val held = ConcurrentHashMap.newKeySet<String>()
    fun tryAcquire(profileId: String) = held.add(profileId)
    fun release(profileId: String) { held.remove(profileId) }
    fun isHeld(profileId: String) = profileId in held
}
