package app.openread.engine

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.Wrapper
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JsTimeoutException : RuntimeException("脚本执行超时")

/**
 * Runs the JavaScript found in book sources.
 *
 * Source JS comes from subscriptions anyone can publish, so it is untrusted: only a short allow-list of
 * classes is visible to scripts (strings, collections, dates, crypto, jsoup and this engine's bridge
 * objects). Files, processes, reflection and the Android framework are not reachable.
 */
object JsRuntime {
    private const val TIMEOUT_MS = 30_000L
    private const val START = "openread.start"

    private val allowedPrefixes = listOf(
        "java.util.regex.", "java.text.", "java.nio.charset.",
        "javax.crypto.", "org.jsoup.nodes.", "org.jsoup.select.",
        // Collection implementations that come back from the bridge objects or the JDK.
        "kotlin.collections.", "java.util.Collections$", "java.util.Arrays$", "java.util.ImmutableCollections$",
        "java.util.ArrayList$", "java.util.HashMap$", "java.util.LinkedHashMap$", "java.util.LinkedList$",
    )
    private val allowedExact = setOf(
        "java.lang.String", "java.lang.Math", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
        "java.lang.Float", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Character",
        "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Number", "java.lang.Object",
        "java.lang.CharSequence", "java.lang.Iterable", "java.lang.Comparable",
        "java.util.ArrayList", "java.util.LinkedList", "java.util.HashMap", "java.util.LinkedHashMap",
        "java.util.TreeMap", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
        "java.util.List", "java.util.Map", "java.util.Set", "java.util.Collection", "java.util.Iterator",
        "java.util.Arrays", "java.util.Collections", "java.util.Date", "java.util.UUID", "java.util.Base64",
        "java.util.Random", "java.util.Locale", "java.util.TimeZone", "java.util.Calendar",
        "java.net.URLEncoder", "java.net.URLDecoder", "java.net.URI",
        "java.security.MessageDigest", "java.security.SecureRandom",
        "org.jsoup.Jsoup",
        // Only the objects handed to scripts: never Http, Mirrors, Probe or JsRuntime itself.
        "app.openread.engine.JsExtensions", "app.openread.engine.StrResponse", "app.openread.engine.CacheBridge",
        "app.openread.engine.CookieBridge", "app.openread.engine.SourceBridge", "app.openread.engine.BookBridge",
        "app.openread.engine.ChapterBridge", "app.openread.engine.SymmetricCrypto", "app.openread.engine.AnalyzeRule",
    )
    private val shutter = ClassShutter { name -> name in allowedExact || allowedPrefixes.any { name.startsWith(it) } }

    private val wrapFactory = object : WrapFactory() {}.apply {
        isJavaPrimitiveWrap = false // keep Java strings/numbers as JS primitives, so `result.replace(/x/g, "")` is JS's
    }

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1 // interpreted: bytecode generation is unavailable on Android
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 20_000
            maximumInterpreterStackDepth = 200 // runaway recursion must end in a script error, not an OutOfMemoryError
            setClassShutter(shutter)
            wrapFactory = this@JsRuntime.wrapFactory
        }

        /** E4X (`<xml/>` literals) needs a JAXP parser feature Android's does not have; initialising it fails on every call. */
        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_E4X) false else super.hasFeature(cx, featureIndex)

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val start = cx.getThreadLocal(START) as? Long ?: return
            if (System.currentTimeMillis() - start > TIMEOUT_MS) throw JsTimeoutException()
        }
    }

    /** Standard objects built once and sealed: scripts cannot change `Object.prototype` for the next source. */
    private val shared: ScriptableObject by lazy { factory.call { cx -> cx.initStandardObjects(null, true).also { it.sealObject() } } }

    private class Lib(val script: Script?)
    private val libs = ConcurrentHashMap<String, Lib>()

    /** `jsLib` is compiled once per distinct text and then run inside every call's own scope, so its functions see `java`, `source`… */
    private fun lib(cx: Context, text: String): Script? =
        libs.getOrPut(text) { Lib(runCatching { cx.compileString(text, "jsLib", 1, null) }.getOrNull()) }.script

    private val inScript = ThreadLocal<Boolean>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "openread-js").apply { isDaemon = true } }

    /**
     * Runs [js] with [bindings] as globals. A call from inside a script (`java.getString(...)`) runs inline under the outer
     * call's deadline; a top-level call runs on a worker thread and is abandoned after the deadline, because a catastrophic
     * regex or a huge native call cannot be interrupted from inside the interpreter.
     */
    fun eval(js: String, bindings: Map<String, Any?>, jsLib: String? = null): Any? {
        if (inScript.get() == true) return run(js, bindings, jsLib)
        val future = pool.submit(Callable {
            inScript.set(true)
            try { run(js, bindings, jsLib) } finally { inScript.remove() }
        })
        try {
            return future.get(TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw JsTimeoutException()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun run(js: String, bindings: Map<String, Any?>, jsLib: String?): Any? = factory.call { cx ->
        val outermost = cx.getThreadLocal(START) == null
        if (outermost) cx.putThreadLocal(START, System.currentTimeMillis())
        try {
            val scope = cx.newObject(shared)
            scope.prototype = shared
            scope.parentScope = null
            for ((k, v) in bindings) ScriptableObject.putProperty(scope, k, Context.javaToJS(v, scope))
            if (!jsLib.isNullOrBlank()) runCatching { lib(cx, jsLib)?.exec(cx, scope) }
            convert(cx.evaluateString(scope, js, "source", 1, null))
        } finally {
            if (outermost) cx.removeThreadLocal(START)
        }
    }

    /** Turns what Rhino returns into plain Kotlin values: String, Number, Boolean, List, Map or null. */
    fun convert(o: Any?): Any? = when (o) {
        null, Undefined.instance -> null
        is Wrapper -> convert(o.unwrap())
        is CharSequence -> o.toString()
        is NativeArray -> o.map { convert(it) }
        is NativeObject -> o.entries.associateTo(LinkedHashMap()) { it.key.toString() to convert(it.value) }
        is Scriptable -> o.toString()
        else -> o
    }
}
