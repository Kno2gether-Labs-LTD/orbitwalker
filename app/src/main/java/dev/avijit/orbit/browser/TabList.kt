package dev.avijit.orbit.browser

/** One browser screen's tabs, in strip order, with the selected one. Not thread-safe (main thread). */
class TabList<T> {
    private val order = ArrayList<Long>()
    private val tabs = HashMap<Long, T>()

    var selected: Long? = null
        private set
    val ids: List<Long> get() = order.toList()
    val isEmpty: Boolean get() = order.isEmpty()
    val size: Int get() = order.size

    operator fun get(id: Long): T? = tabs[id]

    fun add(id: Long, tab: T, select: Boolean = true) {
        val at = selected?.let { order.indexOf(it) + 1 } ?: order.size
        order.add(at.coerceIn(0, order.size), id)
        tabs[id] = tab
        if (select || selected == null) selected = id
    }

    fun select(id: Long) { if (id in tabs) selected = id }

    /** Removes [id] and returns it (to destroy, or to move to another screen); selection moves right, else left. */
    fun close(id: Long): T? {
        val i = order.indexOf(id)
        if (i < 0) return null
        order.removeAt(i)
        if (selected == id) selected = order.getOrNull(i) ?: order.getOrNull(i - 1)
        return tabs.remove(id)
    }

    /** Selects the next tab (wrapping) and returns it. */
    fun next(): Long? {
        if (order.isEmpty()) return null
        val i = selected?.let { order.indexOf(it) } ?: -1
        return order[(i + 1) % order.size].also { selected = it }
    }
}
