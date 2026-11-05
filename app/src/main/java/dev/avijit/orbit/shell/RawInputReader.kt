package dev.avijit.orbit.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.avijit.orbit.input.EvdevParser
import dev.avijit.orbit.input.MouseFrame
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * Runs in the shell helper (uid 2000, in the `input` group): reads external (Bluetooth/USB)
 * mice and keyboards from /dev/input and grabs them (EVIOCGRAB) so Android doesn't act on them.
 * Rescans every [RESCAN_MS] so devices that sleep and reconnect (new eventN) are picked up again.
 * Grabs end when the files close or this process dies.
 */
class RawInputReader(private val listener: IRawInputListener, private val grab: Boolean) {

    private class Dev(val path: String, val name: String, val mouse: Boolean, val keyboard: Boolean)

    @Volatile private var running = false
    /** Devices currently open (and grabbed), by path. */
    private val open = java.util.concurrent.ConcurrentHashMap<String, Dev>()
    private var scanner: Thread? = null
    @Volatile var lastError: String? = null
        private set

    /** Opens and grabs what's connected now (synchronously), then keeps rescanning. Returns devices grabbed. */
    /** New /dev/input nodes (a device waking or connecting) trigger an immediate rescan. */
    @Suppress("DEPRECATION")
    private val watcher = object : android.os.FileObserver("/dev/input", CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (running && path?.startsWith("event") == true) {
                Thread.sleep(150) // let the node's permissions settle
                synchronized(this@RawInputReader) { if (running) rescan() }
            }
        }
    }

    fun start(): Int {
        running = true
        runCatching { watcher.startWatching() }.onFailure { lastError = "watch /dev/input: ${it.describe()}" }
        synchronized(this) { rescan() }
        scanner = Thread({
            while (running) {
                try { Thread.sleep(RESCAN_MS) } catch (_: InterruptedException) { break }
                if (running) synchronized(this) { rescan() }
            }
        }, "raw-input-scan").apply { isDaemon = true; start() }
        return open.size
    }

    fun openCount(): Int = if (running) open.size else -1

    private val readers = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    /** Stops and waits (≤ ~0.5 s) for readers to close, so a new reader's grab can't hit EBUSY. */
    fun stop() {
        running = false
        runCatching { watcher.stopWatching() }
        scanner?.interrupt()
        scanner = null
        readers.forEach { it.join(POLL_MS * 2L + 100) }
        readers.clear()
    }

    private fun rescan() {
        var changed = false
        for (d in scan()) {
            if (open.containsKey(d.path)) continue
            if (!verify(d)) { lastError = "skipped ${d.path}: device description didn't check out"; continue }
            val stream = try { FileInputStream(d.path) } catch (t: Throwable) { lastError = "open ${d.name}: ${t.describe()}"; continue }
            if (grab && !grab(stream.fd, d)) { runCatching { stream.close() }; continue }
            open[d.path] = d
            changed = true
            readers += Thread({ readLoop(d, stream) }, "raw-input-${d.path.substringAfterLast('/')}").apply { isDaemon = true; start() }
        }
        if (changed) report()
    }

    private fun report() {
        runCatching {
            listener.onDevices(open.values.joinToString("; ") { "${it.name} (${listOfNotNull("mouse".takeIf { _ -> it.mouse }, "keyboard".takeIf { _ -> it.keyboard }).joinToString("+")})" })
        }
    }

    /**
     * Lists devices with `getevent -i`: SELinux blocks the shell user from /sys/class/input, but
     * getevent's ioctls are allowed. Only external (Bluetooth/USB) mice and keyboards are kept.
     */
    private fun scan(): List<Dev> {
        val text = getevent() ?: return emptyList()
        return dev.avijit.orbit.input.GeteventParser.parse(text)
            .filter { anyBus || it.bus == BUS_BLUETOOTH || it.bus == BUS_USB }
            .filter { it.isMouse || it.isKeyboard }
            .map { Dev(it.path, it.name, it.isMouse, it.isKeyboard) }
    }

    /** Runs `getevent -i [path]` with a hard 3 s limit (the process is always reaped). */
    private fun getevent(path: String? = null): String? {
        val p = try {
            ProcessBuilder(listOfNotNull("getevent", "-i", path)).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            lastError = "getevent: ${t.describe()}"
            return null
        }
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { lastError = "getevent timed out"; return null }
            reader.join(500)
            return out.toString()
        } finally {
            p.destroy()
        }
    }

    /** Re-describes one device on its own and requires the same kind of device (anti-spoofing). */
    private fun verify(d: Dev): Boolean {
        val text = getevent(d.path) ?: return false
        val one = dev.avijit.orbit.input.GeteventParser.parseSingle(text, d.path) ?: return false
        return (anyBus || one.bus == BUS_BLUETOOTH || one.bus == BUS_USB) && one.isMouse == d.mouse && one.isKeyboard == d.keyboard
    }

    private fun readLoop(d: Dev, input: FileInputStream) {
        try {
            val fd: FileDescriptor = input.fd
            val frame = MouseFrame()
            val buf = ByteArray(EvdevParser.SIZE * 64)
            val poll = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
            while (running) {
                val ready = try {
                    Os.poll(poll, POLL_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    lastError = "poll ${d.name}: ${e.describe()}"; break
                }
                if (ready <= 0) continue
                val n = try { input.read(buf) } catch (t: Throwable) { break } // ENODEV: device went to sleep / disconnected
                if (n <= 0) break
                var off = 0
                while (off + EvdevParser.SIZE <= n) {
                    val e = EvdevParser.parse(buf, off)
                    off += EvdevParser.SIZE
                    if (e.type == EvdevParser.EV_KEY && e.code < BTN_MISC) {
                        runCatching { listener.onKey(e.code, e.value) }
                    } else {
                        frame.feed(e.type, e.code, e.value)?.let { r -> runCatching { listener.onMouse(r.dx, r.dy, r.buttons, r.wheel) } }
                    }
                }
            }
        } finally {
            runCatching { input.close() } // releases the grab
            open.remove(d.path)
            report()
        }
    }

    /** EVIOCGRAB: any non-null argument grabs; Os.ioctlInt passes a pointer. Returns true if grabbed. */
    private fun grab(fd: FileDescriptor, d: Dev, retried: Boolean = false): Boolean {
        val r = runCatching {
            Os::class.java.getMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType).invoke(null, fd, EVIOCGRAB)
        }.recoverCatching {
            val refClass = Class.forName("android.system.Int32Ref")
            val ref = refClass.getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            Os::class.java.getMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType, refClass).invoke(null, fd, EVIOCGRAB, ref)
        }
        if (r.isFailure && !retried) {
            // EBUSY right after a restart (the old reader is still closing): try once more shortly.
            Thread.sleep(250)
            return grab(fd, d, retried = true)
        }
        r.exceptionOrNull()?.let { lastError = "grab ${d.name}: ${it.describe()}"; return false }
        return true
    }

    companion object {
        /** Tests only (emulator): accept devices on any bus. */
        @Volatile var anyBus = false
        private const val BUS_USB = 0x03
        private const val BUS_BLUETOOTH = 0x05
        private const val BTN_MISC = 0x100
        private const val EVIOCGRAB = 0x40044590
        private const val POLL_MS = 200
        /** Fallback rescan; new devices are normally caught instantly by the watcher. */
        private const val RESCAN_MS = 3000L
    }
}
