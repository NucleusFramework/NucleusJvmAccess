package dev.nucleusframework.nja.server;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.StackValue;
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
 * the same thread. Messages are buffers of the C heap: each side frees the ones it receives.
 */
public final class GraalLink {
    private GraalLink() {}

    /** The app's callback entry: answers a request (freed by the caller), the answer's length in its third argument. */
    interface Upcall extends CFunctionPointer {
        @InvokeCFunctionPointer
        CCharPointer invoke(CCharPointer request, int length, CIntPointer answerLength);
    }

    private static Upcall upcall;

    private static final Link LINK = GraalLink::exchange;

    @CEntryPoint(name = "nja_graal_set_upcall")
    static void setUpcall(IsolateThread thread, Upcall function) {
        upcall = function;
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
        CIntPointer answerLength = StackValue.get(CIntPointer.class);
        CCharPointer answer;
        try {
            answer = upcall.invoke(buffer, request.length, answerLength);
        } finally {
            UnmanagedMemory.free(buffer);
        }
        try {
            return toBytes(answer, answerLength.read());
        } finally {
            UnmanagedMemory.free(answer);
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
