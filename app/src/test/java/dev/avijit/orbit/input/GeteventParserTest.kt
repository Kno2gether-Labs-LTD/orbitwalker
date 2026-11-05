package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeteventParserTest {
    // Real `getevent -i` output shape (emulator keyboard), plus a typical Bluetooth mouse and a touchscreen.
    private val sample = """
add device 1: /dev/input/event12
  bus:      0006
  vendor    0627
  product   0001
  version   0001
  name:     "qwerty2"
  location: "virtio21/input0"
  id:       ""
  version:  1.0.1
  events:
    KEY (0001): 0001  0002  0003  001c  001d  001e  001f  0020 
                002a  002b  002c  002d  0039  0110 
    REL (0002): 0006  0008 
  input props:
    <none>
add device 2: /dev/input/event20
  bus:      0005
  vendor    046d
  product   b023
  version   0001
  name:     "MX Master 3"
  location: "aa:bb:cc:dd:ee:ff"
  id:       ""
  version:  1.0.1
  events:
    KEY (0001): 0110  0111  0112  0113  0114 
    REL (0002): 0000  0001  0006  0008  000b  000c 
    MSC (0004): 0004 
  input props:
    <none>
add device 3: /dev/input/event5
  bus:      0006
  vendor    0000
  product   0000
  version   0000
  name:     "virtio_input_multi_touch_5"
  location: ""
  id:       ""
  version:  1.0.1
  events:
    KEY (0001): 0141  014b 
    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                0001  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
""".trimIndent()

    @Test fun parsesAllDevices() {
        val d = GeteventParser.parse(sample)
        assertEquals(listOf("/dev/input/event12", "/dev/input/event20", "/dev/input/event5"), d.map { it.path })
        assertEquals("MX Master 3", d[1].name)
        assertEquals(0x05, d[1].bus)
    }

    @Test fun classifiesKeyboardMouseAndTouch() {
        val (kb, mouse, touch) = GeteventParser.parse(sample)
        assertTrue(kb.isKeyboard); assertFalse(kb.isMouse)
        assertTrue(mouse.isMouse); assertFalse(mouse.isKeyboard)
        assertFalse(touch.isMouse); assertFalse(touch.isKeyboard)
    }

    @Test fun abilityLinesWithValuesDontBreakParsing() {
        val touch = GeteventParser.parse(sample)[2]
        assertTrue(0x141 in touch.keys)
    }

    @Test fun forgedHeaderInsideADeviceName_isRejected() {
        // A device whose name smuggles a fake "add device" block (to impersonate another device).
        val forged = "add device 1: /dev/input/event30\n  bus:      0005\n  name:     \"Evil\n" +
            "add device 2: /dev/input/event1\n  bus:      0005\n  name:     x\"\n  events:\n" +
            "    KEY (0001): 001c  001e  002c\n"
        val single = GeteventParser.parseSingle(forged, "/dev/input/event30")
        assertEquals(null, single)
    }

    @Test fun pathsMustBeEventNodes() {
        val bad = "add device 1: /data/local/tmp/fake\n  bus:      0005\n  name:     \"k\"\n  events:\n    KEY (0001): 001c  001e  002c\n"
        assertTrue(GeteventParser.parse(bad).isEmpty())
    }

    @Test fun duplicatePaths_areDropped() {
        val dup = sample + "\nadd device 9: /dev/input/event20\n  bus:      0005\n  name:     \"dup\"\n"
        assertTrue(GeteventParser.parse(dup).none { it.path == "/dev/input/event20" })
    }

    @Test fun singleDeviceQuery_acceptsAnHonestDevice() {
        val one = GeteventParser.parseSingle(sample.substringAfter("add device 2").let { "add device 2$it" }.substringBefore("add device 3"), "/dev/input/event20")
        assertEquals("MX Master 3", one?.name)
    }
}
