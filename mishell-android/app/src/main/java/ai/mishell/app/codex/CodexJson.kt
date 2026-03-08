package ai.mishell.app.codex

import org.json.JSONArray
import org.json.JSONObject

internal fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val json = JSONObject()
    pairs.forEach { (key, value) ->
        if (value != null) {
            json.put(key, value.toJsonValue())
        }
    }
    return json
}

internal fun Iterable<Any?>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put(it.toJsonValue()) }
    return array
}

internal fun Any?.toJsonValue(): Any? = when (this) {
    null -> JSONObject.NULL
    is JSONObject -> this
    is JSONArray -> this
    is Iterable<*> -> this.toJsonArray()
    is Array<*> -> this.asIterable().toJsonArray()
    is Map<*, *> -> {
        val json = JSONObject()
        forEach { (key, value) ->
            if (key is String) {
                json.put(key, value.toJsonValue())
            }
        }
        json
    }
    else -> this
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    opt(name).takeUnless { it == null || it == JSONObject.NULL }?.toString()

internal fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    opt(name).takeUnless { it == null || it == JSONObject.NULL } as? Boolean

internal fun JSONObject.optLongOrNull(name: String): Long? {
    val value = opt(name).takeUnless { it == null || it == JSONObject.NULL } ?: return null
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    val value = opt(name).takeUnless { it == null || it == JSONObject.NULL } ?: return null
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optJSONObjectOrNull(name: String): JSONObject? =
    opt(name).takeUnless { it == null || it == JSONObject.NULL } as? JSONObject

internal fun JSONObject.optJSONArrayOrNull(name: String): JSONArray? =
    opt(name).takeUnless { it == null || it == JSONObject.NULL } as? JSONArray

internal fun JSONArray.toStringList(): List<String> =
    buildList(length()) {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value != null && value != JSONObject.NULL) {
                add(value.toString())
            }
        }
    }

internal fun JSONArray.toJsonObjectList(): List<JSONObject> =
    buildList(length()) {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value is JSONObject) {
                add(value)
            }
        }
    }

internal fun Any?.requestIdKey(): String = when (this) {
    null -> ""
    else -> toString()
}
