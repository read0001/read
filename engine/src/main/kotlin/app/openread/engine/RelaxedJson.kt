package app.openread.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Book sources are written by hand, so the JSON inside them is often really a JavaScript object literal:
 * single-quoted strings, unquoted keys, trailing commas, comments. This parses all of that.
 */
internal class RelaxedJson private constructor(private val s: String) {
    private var i = 0

    private fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")

    private fun skip() {
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() || c == '﻿' -> i++
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2 }
                else -> return
            }
        }
    }

    private fun value(): JsonElement {
        skip()
        if (i >= s.length) fail()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"', '\'' -> JsonPrimitive(str(c))
            else -> bare()
        }
    }

    private fun obj(): JsonElement {
        i++
        val m = LinkedHashMap<String, JsonElement>()
        while (true) {
            skip()
            if (i >= s.length) fail()
            if (s[i] == '}') { i++; break }
            val key = if (s[i] == '"' || s[i] == '\'') str(s[i]) else identifier()
            skip()
            if (i >= s.length || s[i] != ':') fail()
            i++
            m[key] = value()
            skip()
            if (i < s.length && s[i] == ',') i++
        }
        return JsonObject(m)
    }

    private fun arr(): JsonElement {
        i++
        val l = ArrayList<JsonElement>()
        while (true) {
            skip()
            if (i >= s.length) fail()
            if (s[i] == ']') { i++; break }
            l.add(value())
            skip()
            if (i < s.length && s[i] == ',') i++
        }
        return JsonArray(l)
    }

    private fun str(q: Char): String {
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != q) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i + 1, minOf(i + 5, s.length)).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(c)
            i++
        }
        if (i >= s.length) fail()
        i++
        return sb.toString()
    }

    private fun identifier(): String {
        val st = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
        if (st == i) fail()
        return s.substring(st, i)
    }

    private fun bare(): JsonElement {
        val st = i
        while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace()) i++
        val t = s.substring(st, i)
        if (t.isEmpty()) fail()
        return when {
            t == "true" -> JsonPrimitive(true)
            t == "false" -> JsonPrimitive(false)
            t == "null" -> JsonNull
            t.toLongOrNull() != null -> JsonPrimitive(t.toLong())
            t.toDoubleOrNull() != null -> JsonPrimitive(t.toDouble())
            else -> JsonPrimitive(t)
        }
    }

    companion object {
        fun parse(text: String): JsonElement? = runCatching {
            val p = RelaxedJson(text)
            val v = p.value()
            p.skip()
            if (p.i != text.length) null else v
        }.getOrNull()
    }
}
