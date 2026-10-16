package dev.nucleusframework.nja.server

import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM objects the app holds, by handle. An object keeps one handle however many times it crosses the bridge: each
 * crossing adds a reference the app releases once (close() or its proxy's collection).
 */
object Handles {
    private class Entry(val value: Any) {
        var references = 0
    }

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val ids = IdentityHashMap<Any, Long>()
    private var nextId = 1L

    /** The handle the app gets for [value] (one more reference to release). */
    fun export(value: Any): Long = synchronized(ids) {
        val existing = ids[value]
        if (existing != null) {
            entries.getValue(existing).references++
            return existing
        }
        val id = nextId++
        entries[id] = Entry(value).also { it.references = 1 }
        ids[value] = id
        id
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(handle: Long): T {
        val entry = entries[handle] ?: throw IllegalStateException("NJA: unknown or released JVM object handle $handle")
        return entry.value as T
    }

    fun release(handle: Long) {
        synchronized(ids) {
            val entry = entries[handle] ?: return
            if (--entry.references <= 0) {
                entries.remove(handle)
                ids.remove(entry.value)
            }
        }
    }

    val size: Int get() = entries.size
}
