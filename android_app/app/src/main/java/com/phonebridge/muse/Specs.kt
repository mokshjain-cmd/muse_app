package com.phonebridge.muse

import org.json.JSONObject

/** Helpers for describing commands to Muse and building their results. */
object Specs {
    /** One parameter: name, description, and optionally its JSON type (default string). */
    data class P(val name: String, val description: String, val type: String = "string")

    fun spec(description: String, required: List<P> = emptyList(), optional: List<P> = emptyList(), timeoutMs: Int? = null): JSONObject =
        JSONObject().put("description", description).put("required", params(required)).put("optional", params(optional))
            .also { if (timeoutMs != null) it.put("timeout_ms", timeoutMs) }

    private fun params(list: List<P>) = JSONObject().apply {
        list.forEach { put(it.name, JSONObject().put("type", it.type).put("description", it.description)) }
    }

    fun ok(payload: JSONObject = JSONObject()): JSONObject = JSONObject().put("ok", true).put("payload", payload)
    fun err(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    /** Thrown by a command for a user-facing error (bad or missing input, not set up). */
    class Fail(message: String) : Exception(message)

    fun JSONObject.need(key: String): String = optString(key).ifEmpty { throw Fail("$key is required") }
}
