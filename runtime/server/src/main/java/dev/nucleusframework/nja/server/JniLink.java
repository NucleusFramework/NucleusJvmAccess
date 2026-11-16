package dev.nucleusframework.nja.server;

/**
 * Kotlin/Native (iOS, macOS, Linux, Windows): the JVM runs in the app's process and its threads (Kotlin/Native ones,
 * attached to it), so calls and callbacks are plain JNI calls on the calling thread.
 */
public final class JniLink {
    private JniLink() {}

    private static final Link LINK = JniLink::upcall;

    /** Serves a call of the app. */
    static byte[] dispatch(byte[] message) {
        return NjaServer.handle(message, LINK);
    }

    /** Runs a callback's request in the app, returns its answer. Registered by the app's runtime. */
    static native byte[] upcall(byte[] request);
}
