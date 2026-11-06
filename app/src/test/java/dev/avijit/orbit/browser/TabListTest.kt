package dev.avijit.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabListTest {
    private fun list(vararg ids: Long) = TabList<String>().apply { ids.forEach { add(it, "t$it") } }

    @Test fun add_selectsTheNewTab() {
        val l = list(1, 2, 3)
        assertEquals(listOf(1L, 2L, 3L), l.ids)
        assertEquals(3L, l.selected)
    }

    @Test fun add_inBackground_keepsSelection() {
        val l = list(1)
        l.add(2, "t2", select = false)
        assertEquals(1L, l.selected)
    }

    @Test fun closeSelected_selectsTheTabToItsRight_elseLeft() {
        val l = list(1, 2, 3)
        l.select(2)
        l.close(2)
        assertEquals(3L, l.selected)
        l.close(3)
        assertEquals(1L, l.selected)
    }

    @Test fun closeOther_keepsSelection() {
        val l = list(1, 2, 3)
        l.close(1)
        assertEquals(3L, l.selected)
    }

    @Test fun closeLast_leavesEmpty() {
        val l = list(1)
        assertEquals("t1", l.close(1))
        assertTrue(l.isEmpty)
        assertNull(l.selected)
    }

    @Test fun detach_removesWithoutLosingTheTab() {
        val l = list(1, 2)
        assertEquals("t2", l.close(2))
        assertEquals(listOf(1L), l.ids)
        assertNull(l.close(9))
    }

    @Test fun next_cyclesAndWraps() {
        val l = list(1, 2, 3)
        l.select(3)
        assertEquals(1L, l.next())
        assertEquals(2L, l.next())
    }
}
