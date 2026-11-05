package dev.avijit.orbit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDockTest {
    @Test fun push_movesToFront_noDuplicates_capped() {
        var r = emptyList<String>()
        (1..10).forEach { r = AppDock.push(r, "a$it") }
        assertEquals(AppDock.MAX, r.size)
        assertEquals("a10", r.first())
        r = AppDock.push(r, "a7")
        assertEquals(listOf("a7", "a10", "a9", "a8", "a6", "a5", "a4", "a3"), r)
    }

    @Test fun indexAt_splitsEvenly() {
        assertEquals(0, AppDock.indexAt(0.0f, 4))
        assertEquals(1, AppDock.indexAt(0.26f, 4))
        assertEquals(3, AppDock.indexAt(0.999f, 4))
        assertEquals(3, AppDock.indexAt(1.0f, 4))
        assertNull(AppDock.indexAt(0.5f, 0))
        assertNull(AppDock.indexAt(-0.1f, 3))
    }

    @Test fun indexAt_skipsTheWidgetSection() {
        // 3 widget slots + 3 apps = 6 slots across the dock.
        assertNull(AppDock.indexAt(0.2f, 3, widgetSlots = 3f))
        assertEquals(0, AppDock.indexAt(0.55f, 3, widgetSlots = 3f))
        assertEquals(2, AppDock.indexAt(0.99f, 3, widgetSlots = 3f))
    }

    @Test fun pose_withWidgets_isWider() {
        val anchor = ScreenPose(0f, 0f, 1.5f, 1.2f, 0.675f)
        assertEquals(AppDock.pose(anchor, 5).widthM, AppDock.pose(anchor, 2, widgetSlots = 3f).widthM, 1e-4f)
        assertTrue(AppDock.pose(anchor, 0, widgetSlots = 3f).widthM > 0f)
    }

    @Test fun pose_isBelowAnchor_centredAndNearer_widthGrowsWithCount() {
        val anchor = ScreenPose(0f, 0f, 1.5f, 1.2f, 0.675f)
        val p3 = AppDock.pose(anchor, 3)
        val p6 = AppDock.pose(anchor, 6)
        assertEquals(0f, p3.yawDeg, 1e-4f)
        assertTrue(p3.pitchDeg < -12f) // under the screen's bottom edge
        assertTrue(p3.distanceM < anchor.distanceM)
        assertEquals(p3.widthM * 2, p6.widthM, 1e-4f)
        assertEquals(p3.heightM, p6.heightM, 1e-4f)
        assertEquals(AppDock.ITEM_ASPECT * 3, p3.widthM / p3.heightM, 1e-3f)
    }
}
