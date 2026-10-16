package dev.nucleusframework.nja.server;

/**
 * Android: the JVM's side of the channels the NJA bridge pairs the app's threads with (see nja_android.c). Each app
 * thread calling into the JVM gets a worker thread of its own, running {@link #workerLoop} on their channel.
 */
public final class NativeLink {
    private NativeLink() {}

    /** The app thread's next message, or null once it is gone. Registered by the bridge. */
    static native byte[] await(long channel);

    /** Hands a message to the app thread. Registered by the bridge. */
    static native void send(long channel, byte[] message, int length);

    static void boot() {
        NjaServer.boot();
    }

    static void workerLoop(long channel) {
        Link link = request -> exchange(channel, request);
        byte[] message;
        while ((message = await(channel)) != null) {
            byte[] reply = NjaServer.handle(message, link);
            send(channel, reply, reply.length);
        }
    }

    /**
     * Sends a callback's request and waits for its answer, serving the calls the app makes meanwhile (from the
     * callback, on the same thread): they come on the same channel, as nested calls.
     */
    private static byte[] exchange(long channel, byte[] request) {
        send(channel, request, request.length);
        for (;;) {
            byte[] message = await(channel);
            if (message == null) throw new IllegalStateException("NJA: the app's thread is gone");
            if (message[0] != Protocol.CALL) return message;
            byte[] reply = NjaServer.handle(message, request2 -> exchange(channel, request2));
            send(channel, reply, reply.length);
        }
    }
}
