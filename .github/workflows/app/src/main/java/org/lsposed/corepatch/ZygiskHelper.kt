package org.lsposed.corepatch

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

typealias BeforeCallback = (ZygiskHelper.HookCallback) -> Unit
typealias AfterCallback = (ZygiskHelper.AfterHookCallback) -> Unit

/**
 * Pure-Zygisk Java hook runtime.
 *
 * The actual ART patching is provided by LSPlant from the native Zygisk library.
 * No LSPosed/libxposed API is referenced here.
 */
object ZygiskHelper {
    lateinit var hostClassLoader: ClassLoader
        private set

    private val hooks = mutableListOf<Hooker>()
    private val backups = java.util.IdentityHashMap<Executable, Executable>()

    fun setHostClassLoader(classLoader: ClassLoader) {
        hostClassLoader = classLoader
    }

    fun install(executable: Executable, before: BeforeCallback = {}, after: AfterCallback = {}): Boolean {
        return try {
            val hooker = Hooker(executable, before, after)
            val backup = nativeHook(
                executable,
                hooker,
                Hooker.callbackMethod
            ) ?: return false
            hooker.backup = backup
            synchronized(hooks) {
                hooks += hooker
                backups[executable] = backup
            }
            true
        } catch (t: Throwable) {
            log("hook failed for ${executable}", t)
            false
        }
    }

    fun hookBefore(member: Executable, callback: BeforeCallback): Boolean =
        install(member, before = callback)

    fun hookAfter(member: Executable, callback: AfterCallback): Boolean =
        install(member, after = callback)

    fun getOriginInvoker(method: Method): OriginInvoker? {
        val backup = synchronized(hooks) { backups[method] }
        return OriginInvoker(backup ?: method)
    }

    fun deoptimize(method: Method): Boolean = try {
        nativeDeoptimize(method)
    } catch (t: Throwable) {
        log("deoptimize failed for $method", t)
        false
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.d("CorePatch-Zygisk", message)
        else Log.e("CorePatch-Zygisk", message, throwable)
    }

    fun findClassIfExists(name: String): Class<*>? = try {
        hostClassLoader.loadClass(name)
    } catch (_: Throwable) {
        null
    }

    fun setStaticBoolean(field: Field, value: Boolean) {
        field.isAccessible = true
        field.setBoolean(null, value)
    }

    class HookCallback internal constructor(
        val executable: Executable,
        val args: Array<Any?>
    ) {
        val thisObject: Any?
            get() = if (executable is Method && java.lang.reflect.Modifier.isStatic(executable.modifiers)) null
            else args.firstOrNull()

        fun returnAndSkip(result: Any?) {
            skipped = true
            skipResult = result
        }

        internal var skipped = false
            private set
        internal var skipResult: Any? = null
            private set
    }

    class AfterHookCallback(
        val executable: Executable,
        val args: Array<Any?>,
        var result: Any?,
        var throwable: Throwable?
    ) {
        val thisObject: Any?
            get() = if (executable is Method && java.lang.reflect.Modifier.isStatic(executable.modifiers)) null
            else args.firstOrNull()
    }

    class OriginInvoker(private val backup: Executable) {
        fun invoke(receiver: Any?, args: Array<Any?>): Any? {
            backup.isAccessible = true
            return try {
                backup.invoke(receiver, *args)
            } catch (e: InvocationTargetException) {
                throw (e.cause ?: e)
            }
        }
    }

    private class Hooker(
        val executable: Executable,
        val before: BeforeCallback,
        val after: AfterCallback
    ) {
        @Volatile var backup: Executable? = null

        public fun callback(args: Array<Any?>): Any? {
            val callback = HookCallback(executable, args)
            before(callback)

            var result: Any? = null
            var throwable: Throwable? = null

            if (!callback.skipped) {
                try {
                    val target = backup ?: error("backup executable missing")
                    val receiver: Any?
                    val invokeArgs: Array<Any?>
                    if (executable is Method && java.lang.reflect.Modifier.isStatic(executable.modifiers)) {
                        receiver = null
                        invokeArgs = args
                    } else {
                        receiver = args.firstOrNull()
                        invokeArgs = if (args.isEmpty()) emptyArray() else args.copyOfRange(1, args.size)
                    }
                    target.isAccessible = true
                    result = when (target) {
                        is Method -> target.invoke(receiver, *invokeArgs)
                        is Constructor<*> -> {
                            // LSPlant can return the original constructor as an Executable.
                            // Constructor invocation creates a new instance, which cannot replace
                            // the already-created receiver. Constructor hooks therefore rely on
                            // LSPlant's generated bridge to execute the original constructor.
                            // Calling the backup here would double-run it.
                            null
                        }
                        else -> target.invoke(receiver, *invokeArgs)
                    }
                } catch (e: InvocationTargetException) {
                    throwable = e.cause ?: e
                } catch (t: Throwable) {
                    throwable = t
                }
            } else {
                result = callback.skipResult
            }

            val afterCallback = AfterHookCallback(executable, args, result, throwable)
            after(afterCallback)
            if (afterCallback.throwable != null) throw afterCallback.throwable!!
            return afterCallback.result
        }

        companion object {
            val callbackMethod: Method = Hooker::class.java.getDeclaredMethod(
                "callback", Array<Any?>::class.java
            ).apply { isAccessible = true }
        }
    }

    @JvmStatic
    private external fun nativeHook(
        target: Executable,
        hooker: Any,
        callback: Method
    ): Executable?

    @JvmStatic
    private external fun nativeDeoptimize(method: Method): Boolean
}
