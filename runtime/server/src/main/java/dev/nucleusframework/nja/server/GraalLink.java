package dev.nucleusframework.nja.server;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

/**
 * A GraalVM native image (the façades compiled ahead of time, linked into the app): the app calls its entry points on
 * its own threads, attached to the image's isolate, and the image calls the app back through a function pointer, on
 * the same thread. Messages are buffers of the C heap, freed by the side that allocated them (on Windows the image's C
 * library isn't the app's): the app frees the image's replies through {@code nja_graal_free}, the image the app's
 * answers through the function it was given. No StackValue: it throws on a virtual thread, which a façade may invoke a
 * callback from.
 */
public final class GraalLink {
    private GraalLink() {}

    /** The app's callback entry: answers a request (freed by the caller), the answer's length in its third argument. */
    interface Upcall extends CFunctionPointer {
        @InvokeCFunctionPointer
        CCharPointer invoke(CCharPointer request, int length, CIntPointer answerLength);
    }

    /** Frees a buffer of the app (an answer). */
    interface Release extends CFunctionPointer {
        @InvokeCFunctionPointer
        void invoke(CCharPointer buffer);
    }

    private static Upcall upcall;
    private static Release release;

    private static final Link LINK = GraalLink::exchange;

    @CEntryPoint(name = "nja_graal_set_upcall")
    static void setUpcall(IsolateThread thread, Upcall function, Release releaseFunction) {
        upcall = function;
        release = releaseFunction;
    }

    /** Frees a reply of the image. */
    @CEntryPoint(name = "nja_graal_free")
    static void free(IsolateThread thread, CCharPointer reply) {
        UnmanagedMemory.free(reply);
    }

    /** Serves a call of the app: its reply, malloc'ed, its length written to [replyLength]. */
    @CEntryPoint(name = "nja_graal_dispatch")
    static CCharPointer dispatch(IsolateThread thread, CCharPointer message, int length, CIntPointer replyLength) {
        byte[] reply = NjaServer.handle(toBytes(message, length), LINK);
        replyLength.write(reply.length);
        return toBuffer(reply);
    }

    private static byte[] exchange(byte[] request) {
        CCharPointer buffer = toBuffer(request);
        CIntPointer answerLength = UnmanagedMemory.malloc(Integer.BYTES);
        try {
            CCharPointer answer;
            try {
                answer = upcall.invoke(buffer, request.length, answerLength);
            } finally {
                UnmanagedMemory.free(buffer);
            }
            try {
                return toBytes(answer, answerLength.read());
            } finally {
                release.invoke(answer);
            }
        } finally {
            UnmanagedMemory.free(answerLength);
        }
    }

    private static byte[] toBytes(CCharPointer buffer, int length) {
        byte[] bytes = new byte[length];
        if (length > 0) CTypeConversion.asByteBuffer(buffer, length).get(bytes);
        return bytes;
    }

    private static CCharPointer toBuffer(byte[] bytes) {
        CCharPointer buffer = UnmanagedMemory.malloc(Math.max(bytes.length, 1));
        if (bytes.length > 0) CTypeConversion.asByteBuffer(buffer, bytes.length).put(bytes);
        return buffer;
    }
}
