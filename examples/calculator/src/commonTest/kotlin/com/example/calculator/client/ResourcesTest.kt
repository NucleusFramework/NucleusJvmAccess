package com.example.calculator.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcesTest {
    @Test
    fun `closing the proxy closes the JVM object, once`() {
        val before = Resource.closeCount()
        val resource = Resource("r")
        assertTrue(resource.isOpen())
        resource.close()
        assertEquals(before + 1, Resource.closeCount())
        resource.close()
        assertEquals(before + 1, Resource.closeCount())
        assertFailsWith<IllegalStateException> { resource.isOpen() }
    }

    @Test
    fun `use closes it`() {
        val before = Resource.closeCount()
        Resource("u").use { assertTrue(it.isOpen()) }
        assertEquals(before + 1, Resource.closeCount())
    }

    @Test
    fun `an internal constructor stays the JVM's`() {
        val ticket = TicketOffice().issue("abc")
        assertEquals("abc", ticket.label)
        assertTrue(ticket.check(21))
        assertFalse(ticket.check(1))
    }
}
