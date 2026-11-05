package dev.avijit.orbit.render

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PanelGeometryTest {
    @Test fun topLeft() = assertArrayEquals(floatArrayOf(-0.8f, 0.5f, -1.5f), PanelGeometry.uvToWorld(0f, 0f), 1e-5f)
    @Test fun centre() = assertArrayEquals(floatArrayOf(0f, 0f, -1.5f), PanelGeometry.uvToWorld(0.5f, 0.5f), 1e-5f)
    @Test fun bottomRight() = assertArrayEquals(floatArrayOf(0.8f, -0.5f, -1.5f), PanelGeometry.uvToWorld(1f, 1f), 1e-5f)
}
