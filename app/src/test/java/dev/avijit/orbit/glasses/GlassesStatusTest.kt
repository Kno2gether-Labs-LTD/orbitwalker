package dev.avijit.orbit.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesStatusTest {

    private fun GlassesStatus.connectAndInit() {
        onEvent(GlassesEvent.Attached)
        onEvent(GlassesEvent.PermissionGranted)
        SdkStep.entries.forEach { onEvent(GlassesEvent.StepOk(it)) }
    }

    private fun GlassesStatus.feedSamples(n: Int, startNs: Long = 0, stepNs: Long = 1_000_000) {
        repeat(n) { onEvent(GlassesEvent.Sample(startNs + it * stepNs)) }
    }

    @Test fun happyPath_reachesStreaming() {
        val s = GlassesStatus()
        assertEquals(GlassesState.NoGlasses, s.state)
        assertEquals(GlassesState.AwaitingPermission, s.onEvent(GlassesEvent.Attached))
        assertEquals(GlassesState.Initializing("INITIALIZE"), s.onEvent(GlassesEvent.PermissionGranted))
        s.onEvent(GlassesEvent.StepOk(SdkStep.INITIALIZE))
        // SpaceWalker order: callbacks are registered before the device is started.
        assertEquals(GlassesState.Initializing("REGISTER_RAW"), s.state)
        s.onEvent(GlassesEvent.StepOk(SdkStep.REGISTER_RAW))
        assertEquals(GlassesState.Initializing("START"), s.state)
        s.onEvent(GlassesEvent.StepOk(SdkStep.START))
        s.onEvent(GlassesEvent.StepOk(SdkStep.OPEN_IMU))
        assertEquals(GlassesState.Initializing("waiting for IMU"), s.state)
        s.feedSamples(10)
        assertTrue(s.state is GlassesState.Streaming)
    }

    @Test fun permissionDenied_isTerminalUntilRetry() {
        val s = GlassesStatus()
        s.onEvent(GlassesEvent.Attached)
        assertEquals(GlassesState.PermissionDenied, s.onEvent(GlassesEvent.PermissionDenied))
        assertEquals(GlassesState.PermissionDenied, s.onEvent(GlassesEvent.StepOk(SdkStep.INITIALIZE)))
        assertEquals(GlassesState.PermissionDenied, s.onEvent(GlassesEvent.Sample(5)))
        assertEquals(GlassesState.AwaitingPermission, s.onEvent(GlassesEvent.Retry))
    }

    @Test fun sdkStepFailure_namesStep() {
        val s = GlassesStatus()
        s.onEvent(GlassesEvent.Attached)
        s.onEvent(GlassesEvent.PermissionGranted)
        assertEquals(
            GlassesState.SdkFailed("OPEN_IMU", -3),
            s.onEvent(GlassesEvent.StepFailed(SdkStep.OPEN_IMU, -3)),
        )
        assertEquals(GlassesState.SdkFailed("OPEN_IMU", -3), s.onEvent(GlassesEvent.Sample(1)))
    }

    @Test fun replug_returnsToStreaming() {
        val s = GlassesStatus()
        s.connectAndInit()
        s.feedSamples(10)
        assertTrue(s.state is GlassesState.Streaming)
        assertEquals(GlassesState.NoGlasses, s.onEvent(GlassesEvent.Detached))
        assertEquals(GlassesState.AwaitingPermission, s.onEvent(GlassesEvent.Attached))
        s.onEvent(GlassesEvent.PermissionGranted)
        SdkStep.entries.forEach { s.onEvent(GlassesEvent.StepOk(it)) }
        s.feedSamples(10, startNs = 5_000_000_000)
        assertTrue(s.state is GlassesState.Streaming)
    }

    @Test fun noDisplay_reportsDexHint() {
        val s = GlassesStatus()
        assertEquals("Glasses display not found — turn off Samsung DeX", s.displayHint(false))
        assertNull(s.displayHint(true))
    }

    @Test fun streamingHz_fromTimestamps() {
        val s = GlassesStatus()
        s.connectAndInit()
        s.feedSamples(10)
        val hz = (s.state as GlassesState.Streaming).hz
        assertEquals(1000f, hz, 50f)
    }

    @Test fun permissionGranted_onlyStartsBringUpOnce() {
        val s = GlassesStatus()
        s.onEvent(GlassesEvent.Attached)
        assertTrue(s.grantPermission())
        assertEquals(false, s.grantPermission())
        s.connectAndInit()
        s.feedSamples(10)
        assertEquals(false, s.grantPermission())
        assertTrue(s.state is GlassesState.Streaming)
    }

    @Test fun attached_whileStreaming_isIgnored() {
        val s = GlassesStatus()
        s.connectAndInit()
        s.feedSamples(10)
        assertTrue(s.onEvent(GlassesEvent.Attached) is GlassesState.Streaming)
    }

    @Test fun stalled_whileInitializing_reportsStepAndTimeout() {
        val s = GlassesStatus()
        s.onEvent(GlassesEvent.Attached)
        s.onEvent(GlassesEvent.PermissionGranted)
        s.onEvent(GlassesEvent.StepOk(SdkStep.INITIALIZE))
        s.onEvent(GlassesEvent.StepOk(SdkStep.REGISTER_RAW))
        s.onEvent(GlassesEvent.StepOk(SdkStep.START))
        assertEquals(
            GlassesState.SdkFailed("OPEN_IMU", GlassesStatus.TIMEOUT),
            s.onEvent(GlassesEvent.Stalled),
        )
    }

    @Test fun stalled_waitingForImu_reportsNoData() {
        val s = GlassesStatus()
        s.connectAndInit()
        assertEquals(
            GlassesState.SdkFailed("waiting for IMU", GlassesStatus.TIMEOUT),
            s.onEvent(GlassesEvent.Stalled),
        )
    }

    @Test fun stalled_whileStreaming_isIgnored() {
        val s = GlassesStatus()
        s.connectAndInit()
        s.feedSamples(10)
        assertTrue(s.onEvent(GlassesEvent.Stalled) is GlassesState.Streaming)
    }
}
