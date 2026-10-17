package com.example.calculator

import java.util.concurrent.atomic.AtomicInteger

/** A JVM resource: closing its proxy closes it, once. */
class Resource(val name: String) : AutoCloseable {
    @Volatile private var open = true

    fun isOpen(): Boolean = open

    override fun close() {
        if (open) closes.incrementAndGet()
        open = false
    }

    companion object {
        private val closes = AtomicInteger()

        fun closeCount(): Int = closes.get()
    }
}

/** A class the app gets from a factory only: its constructor is internal, its state private. */
class Ticket internal constructor(private val secret: Long, val label: String) {
    fun check(guess: Long): Boolean = guess == secret
}

class TicketOffice {
    fun issue(label: String): Ticket = Ticket(label.length * 7L, label)
}
