package dev.nucleusframework.nja

/** Message kinds and built-in members of the bridge's protocol (mirrored by the server runtime). */
internal object Protocol {
    // App -> JVM
    const val CALL: Byte = 1
    const val CALLBACK_RETURN: Byte = 2
    const val CALLBACK_THROW: Byte = 3

    // JVM -> app
    const val RETURN: Byte = 10
    const val THROW: Byte = 11
    const val CALLBACK: Byte = 12

    /** The runtime's own module: its members are the ones below. */
    const val RUNTIME_MODULE = 0
    const val RESOLVE_MODULE = 0
    const val RELEASE = 1
    const val CANCEL = 2
    const val GRANT = 3
    const val POLL_EVENTS = 4
    const val PING = 5
    const val STATS = 6

    // Asynchronous events, delivered through POLL_EVENTS
    const val EVENT_RESULT: Byte = 1
    const val EVENT_ERROR: Byte = 2
    const val EVENT_CANCELLED: Byte = 3
    const val EVENT_FLOW_NEXT: Byte = 4
    const val EVENT_FLOW_DONE: Byte = 5
}
