package dev.avijit.orbit.glasses

/** Steps of VITURE SDK bring-up, in SpaceWalker's order (callbacks are registered before start). */
enum class SdkStep { INITIALIZE, REGISTER_RAW, START, OPEN_IMU }

sealed interface GlassesState {
    data object NoGlasses : GlassesState
    data object AwaitingPermission : GlassesState
    data object PermissionDenied : GlassesState
    data class Initializing(val step: String) : GlassesState
    data class SdkFailed(val step: String, val code: Int) : GlassesState
    data class Streaming(val hz: Float) : GlassesState
}

sealed interface GlassesEvent {
    data object Attached : GlassesEvent
    data object Detached : GlassesEvent
    data object PermissionGranted : GlassesEvent
    data object PermissionDenied : GlassesEvent
    data object Retry : GlassesEvent
    data class StepOk(val step: SdkStep) : GlassesEvent
    data class StepFailed(val step: SdkStep, val code: Int) : GlassesEvent
    data class Sample(val timestampNs: Long) : GlassesEvent

    /** Watchdog: bring-up made no progress (an SDK call hung, or no IMU data arrived). */
    data object Stalled : GlassesEvent
}

/** Pure, thread-confined state machine for the glasses connection. */
class GlassesStatus {
    @Volatile
    var state: GlassesState = GlassesState.NoGlasses
        private set

    private var nextStep = 0
    private var firstSampleNs = 0L
    private var sampleCount = 0L

    @Synchronized
    fun onEvent(e: GlassesEvent): GlassesState {
        state = reduce(state, e)
        return state
    }

    /**
     * Applies PermissionGranted and reports whether the caller should start SDK bring-up.
     * True only on the AwaitingPermission → Initializing transition, so duplicate grants
     * (receiver + onNewIntent) never restart a working session.
     */
    @Synchronized
    fun grantPermission(): Boolean {
        val before = state
        onEvent(GlassesEvent.PermissionGranted)
        return before == GlassesState.AwaitingPermission && state is GlassesState.Initializing
    }

    fun displayHint(hasExternalDisplay: Boolean): String? =
        if (hasExternalDisplay) null else "Glasses display not found — turn off Samsung DeX"

    private fun reduce(s: GlassesState, e: GlassesEvent): GlassesState = when (e) {
        GlassesEvent.Attached ->
            if (s is GlassesState.Initializing || s is GlassesState.Streaming) s else GlassesState.AwaitingPermission
        GlassesEvent.Detached -> GlassesState.NoGlasses
        GlassesEvent.Retry -> if (s == GlassesState.NoGlasses) s else GlassesState.AwaitingPermission
        GlassesEvent.PermissionDenied ->
            if (s == GlassesState.AwaitingPermission) GlassesState.PermissionDenied else s
        GlassesEvent.PermissionGranted ->
            if (s == GlassesState.AwaitingPermission) {
                nextStep = 0
                sampleCount = 0
                GlassesState.Initializing(SdkStep.entries[0].name)
            } else s
        is GlassesEvent.StepOk ->
            if (s is GlassesState.Initializing && e.step.ordinal == nextStep) {
                nextStep++
                if (nextStep < SdkStep.entries.size) GlassesState.Initializing(SdkStep.entries[nextStep].name)
                else GlassesState.Initializing(WAITING_FOR_IMU)
            } else s
        is GlassesEvent.StepFailed ->
            if (s is GlassesState.Initializing) GlassesState.SdkFailed(e.step.name, e.code) else s
        is GlassesEvent.Sample -> onSample(s, e.timestampNs)
        GlassesEvent.Stalled ->
            if (s is GlassesState.Initializing) GlassesState.SdkFailed(s.step, TIMEOUT) else s
    }

    private fun onSample(s: GlassesState, ts: Long): GlassesState {
        val ready = (s is GlassesState.Initializing && s.step == WAITING_FOR_IMU) || s is GlassesState.Streaming
        if (!ready) return s
        if (sampleCount == 0L) firstSampleNs = ts
        sampleCount++
        if (sampleCount < MIN_SAMPLES_FOR_RATE) return s
        val spanNs = ts - firstSampleNs
        val hz = if (spanNs > 0) (sampleCount - 1) * 1e9f / spanNs else 0f
        if (sampleCount >= RATE_WINDOW) {
            firstSampleNs = ts
            sampleCount = 1
        }
        return GlassesState.Streaming(hz)
    }

    companion object {
        /** Code reported when the watchdog fires. */
        const val TIMEOUT = -103
        private const val WAITING_FOR_IMU = "waiting for IMU"
        private const val MIN_SAMPLES_FOR_RATE = 5L
        private const val RATE_WINDOW = 2000L
    }
}
