package dev.nucleusframework.nja.server

import java.util.ServiceLoader
import java.util.concurrent.TimeUnit

/**
 * A façade's API, as its generated dispatcher exposes it: [call] runs member [member] with the arguments read from
 * [input] and writes its result to [output].
 */
abstract class NjaModule(val name: String) {
    abstract fun call(member: Int, input: WireReader, output: WireWriter, context: CallContext)
}

/** Sends a callback's request to the app (on the thread that made the call) and returns its answer. */
fun interface Link {
    fun exchange(message: ByteArray): ByteArray
}

/** An exception thrown by one of the app's callbacks, rethrown on the app's side as itself. */
class ClientCallbackException(val token: Long, val className: String, message: String?) : RuntimeException(message)

/** The call being served: lets the façade call the app back while it runs, on its thread. */
class CallContext internal constructor(private val link: Link) {
    private val thread = Thread.currentThread()
    @Volatile private var active = true

    internal fun finish() {
        active = false
    }

    /** Invokes the app's callback [id], valid for the duration of the call that received it. */
    fun <R> invokeCallback(id: Long, encode: (WireWriter) -> Unit, decode: (WireReader) -> R): R {
        check(active && Thread.currentThread() === thread) {
            "NJA: a callback can only be invoked during the call that received it, on its thread"
        }
        val writer = WireWriter()
        writer.writeByte(Protocol.CALLBACK)
        writer.writeLong(id)
        encode(writer)
        val answer = link.exchange(writer.toByteArray())
        val reader = WireReader(answer)
        return when (reader.readByte()) {
            Protocol.CALLBACK_RETURN -> decode(reader)
            Protocol.CALLBACK_THROW -> {
                val token = reader.readLong()
                val className = reader.readString() ?: "Throwable"
                throw ClientCallbackException(token, className, reader.readString())
            }
            else -> throw IllegalStateException("NJA: unexpected callback answer")
        }
    }
}

object NjaServer {
    private val modules = ArrayList<NjaModule>()
    private val moduleIndex = HashMap<String, Int>()

    @Volatile private var booted = false

    @JvmStatic
    fun boot() {
        if (booted) return
        synchronized(this) {
            if (booted) return
            for (module in ServiceLoader.load(NjaModule::class.java, NjaServer::class.java.classLoader)) register(module)
            booted = true
        }
    }

    /** Registers a façade's module (index 1 and up: 0 is the runtime's own). */
    @JvmStatic
    fun register(module: NjaModule) {
        synchronized(this) {
            if (module.name in moduleIndex) return
            modules += module
            moduleIndex[module.name] = modules.size
        }
    }

    /** Serves one message of the app (a call), returns the reply. */
    @JvmStatic
    fun handle(message: ByteArray, link: Link): ByteArray {
        val reader = WireReader(message)
        val output = WireWriter()
        output.writeByte(Protocol.RETURN)
        val kind = reader.readByte()
        if (kind != Protocol.CALL) return failure(IllegalStateException("NJA: unexpected message kind $kind"))
        val context = CallContext(link)
        return try {
            val module = reader.readInt()
            val member = reader.readInt()
            if (module == Protocol.RUNTIME_MODULE) {
                runtimeCall(member, reader, output)
            } else {
                val target = modules.getOrNull(module - 1) ?: throw IllegalStateException("NJA: unknown module $module")
                target.call(member, reader, output, context)
            }
            output.toByteArray()
        } catch (e: Throwable) {
            failure(e)
        } finally {
            context.finish()
        }
    }

    private fun failure(e: Throwable): ByteArray {
        val output = WireWriter()
        output.writeByte(Protocol.THROW)
        writeThrowable(output, e)
        return output.toByteArray()
    }

    /** [token] (the app's own exception, or 0), class name, message, stack trace. */
    fun writeThrowable(output: WireWriter, e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        output.writeLong((cause as? ClientCallbackException)?.token ?: 0L)
        output.writeString((cause as? ClientCallbackException)?.className ?: cause.javaClass.name)
        output.writeString(cause.message)
        output.writeString(cause.stackTrace.take(24).joinToString("\n") { "\tat $it" })
    }

    private fun runtimeCall(member: Int, input: WireReader, output: WireWriter) {
        when (member) {
            Protocol.RESOLVE_MODULE -> {
                boot()
                val name = input.readString()
                output.writeInt(synchronized(this) { moduleIndex[name] ?: -1 })
            }
            Protocol.RELEASE -> repeat(input.readInt()) { Handles.release(input.readLong()) }
            Protocol.CANCEL -> NjaAsync.cancel(input.readLong())
            Protocol.GRANT -> NjaAsync.grant(input.readLong(), input.readInt())
            Protocol.POLL_EVENTS -> NjaAsync.poll(input.readInt().toLong(), TimeUnit.MILLISECONDS, output)
            Protocol.PING -> output.writeBytes(input.readBytes())
            Protocol.STATS -> {
                output.writeInt(Handles.size)
                output.writeInt(NjaAsync.pending)
                output.writeString(System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
            }
            else -> throw IllegalStateException("NJA: unknown runtime member $member")
        }
    }
}
