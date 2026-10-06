package com.phonebridge.google

import com.phonebridge.muse.BoardPort
import com.phonebridge.muse.CommandProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gmail and Google Calendar as Muse commands. The phone calls Google's REST
 * APIs with the user's own access token; Muse only sees the results. Anything
 * that sends or creates something asks Yes/No on the board first.
 */
class GoogleCommands(
    private val auth: GoogleAuth,
    private val board: BoardPort,
    private val log: (String) -> Unit,
) : CommandProvider {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Offered to Muse only after "Sign in with Google" here; otherwise Muse uses its own Gmail/Calendar connectors. */
    override val specs: JSONObject
        get() {
            if (!auth.connected) return JSONObject()
            val tz = "${zone.id} (UTC${OffsetDateTime.now(zone).offset})"
            return JSONObject()
                .put("calendar.list_events", spec(
                    "List events on the user's primary Google Calendar between two times. Times are ISO 8601; " +
                        "the user's time zone is $tz. Returns title, start, end, location and attendees.",
                    req("time_min" to "Start of the range, ISO 8601, e.g. 2026-10-06T00:00:00+05:30"),
                    opt("time_max" to "End of the range, ISO 8601. Default: 24 hours after time_min.",
                        "max_results" to "Maximum events, 1-50. Default 10.", type = mapOf("max_results" to "integer")),
                ))
                .put("calendar.create_event", spec(
                    "Add an event to the user's primary Google Calendar. The user confirms on their desk display " +
                        "first. Times are ISO 8601; the user's time zone is $tz. Returns the event link.",
                    req("title" to "Event title.", "start" to "Start time, ISO 8601."),
                    opt("end" to "End time, ISO 8601. Default: one hour after start.",
                        "description" to "Notes for the event.", "location" to "Where.",
                        "attendees" to "Comma-separated email addresses to invite."),
                ))
                .put("gmail.search", spec(
                    "Search the user's Gmail with Gmail search syntax (e.g. 'from:amazon newer_than:7d', 'is:unread'). " +
                        "Returns id, from, subject, date and snippet for each message.",
                    req("query" to "Gmail search query."),
                    opt("max_results" to "Maximum messages, 1-20. Default 5.", type = mapOf("max_results" to "integer")),
                ))
                .put("gmail.read", spec(
                    "Read one Gmail message by id (from gmail.search). Returns headers and the plain-text body (cut at 8000 characters).",
                    req("id" to "Message id."),
                ))
                .put("gmail.draft", spec(
                    "Create a draft email in the user's Gmail (not sent). Returns the draft id.",
                    req("to" to "Recipient email address(es), comma-separated.", "subject" to "Subject.", "body" to "Plain-text body."),
                    opt("cc" to "Cc address(es), comma-separated."),
                ))
                .put("gmail.send", spec(
                    "Send an email from the user's Gmail. The user confirms on their desk display first; if they decline, " +
                        "nothing is sent. Returns the message id and thread_id (pass it to watch.email_reply to hear back when they answer).",
                    req("to" to "Recipient email address(es), comma-separated.", "subject" to "Subject.", "body" to "Plain-text body."),
                    opt("cc" to "Cc address(es), comma-separated."),
                ))
        }

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("calendar.") && !command.startsWith("gmail.")) return null
        return try {
            when (command) {
                "calendar.list_events" -> listEvents(params)
                "calendar.create_event" -> createEvent(params)
                "gmail.search" -> search(params)
                "gmail.read" -> read(params)
                "gmail.draft" -> draft(params)
                "gmail.send" -> send(params)
                else -> err("unknown command: $command")
            }
        } catch (e: GoogleAuth.NeedsSignIn) {
            err(e.message!!)
        } catch (e: BadInput) {
            err(e.message!!)
        } catch (e: Exception) {
            log("$command failed: ${e.message}")
            err("Google request failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Calendar

    private suspend fun listEvents(p: JSONObject): JSONObject {
        val min = time(p.optString("time_min").ifEmpty { throw BadInput("time_min is required") })
        val max = p.optString("time_max").takeIf { it.isNotEmpty() }?.let(::time) ?: min.plusHours(24)
        val n = p.optInt("max_results", 10).coerceIn(1, 50)
        val url = "$CAL/calendars/primary/events".toHttpUrl().newBuilder()
            .addQueryParameter("timeMin", min.format(ISO)).addQueryParameter("timeMax", max.format(ISO))
            .addQueryParameter("singleEvents", "true").addQueryParameter("orderBy", "startTime")
            .addQueryParameter("maxResults", n.toString()).build().toString()
        val items = get(url).optJSONArray("items") ?: JSONArray()
        val events = JSONArray()
        for (i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            fun when_(k: String) = e.optJSONObject(k)?.let { it.optString("dateTime").ifEmpty { it.optString("date") } } ?: ""
            val attendees = e.optJSONArray("attendees")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("email") } } ?: emptyList()
            events.put(JSONObject().put("title", e.optString("summary")).put("start", when_("start")).put("end", when_("end"))
                .put("location", e.optString("location")).put("attendees", JSONArray(attendees)))
        }
        return ok(JSONObject().put("events", events))
    }

    private suspend fun createEvent(p: JSONObject): JSONObject {
        val title = p.optString("title").ifEmpty { throw BadInput("title is required") }
        val start = time(p.optString("start").ifEmpty { throw BadInput("start is required") })
        val end = p.optString("end").takeIf { it.isNotEmpty() }?.let(::time) ?: start.plusHours(1)
        val attendees = list(p.optString("attendees"))
        val q = "Add \"$title\" on ${start.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))}?"
        if (!confirm(q)) return err("The user declined on the desk display; the event was not created.")

        val body = JSONObject().put("summary", title)
            .put("start", JSONObject().put("dateTime", start.format(ISO)).put("timeZone", zone.id))
            .put("end", JSONObject().put("dateTime", end.format(ISO)).put("timeZone", zone.id))
        p.optString("description").takeIf { it.isNotEmpty() }?.let { body.put("description", it) }
        p.optString("location").takeIf { it.isNotEmpty() }?.let { body.put("location", it) }
        if (attendees.isNotEmpty()) body.put("attendees", JSONArray(attendees.map { JSONObject().put("email", it) }))
        val url = "$CAL/calendars/primary/events" + if (attendees.isNotEmpty()) "?sendUpdates=all" else ""
        val e = post(url, body)
        log("Calendar event added: $title")
        return ok(JSONObject().put("id", e.optString("id")).put("link", e.optString("htmlLink")))
    }

    // ------------------------------------------------------------------ Gmail

    private suspend fun search(p: JSONObject): JSONObject {
        val query = p.optString("query").ifEmpty { throw BadInput("query is required") }
        val n = p.optInt("max_results", 5).coerceIn(1, 20)
        val url = "$GMAIL/messages".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("maxResults", n.toString()).build().toString()
        val ids = get(url).optJSONArray("messages") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until ids.length()) {
            val id = ids.getJSONObject(i).getString("id")
            val m = get("$GMAIL/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
            val h = headers(m)
            out.put(JSONObject().put("id", id).put("from", h["from"] ?: "").put("subject", h["subject"] ?: "")
                .put("date", h["date"] ?: "").put("snippet", m.optString("snippet")))
        }
        return ok(JSONObject().put("messages", out))
    }

    private suspend fun read(p: JSONObject): JSONObject {
        val id = p.optString("id").ifEmpty { throw BadInput("id is required") }
        val m = get("$GMAIL/messages/$id?format=full")
        val h = headers(m)
        val body = plainText(m.optJSONObject("payload")).ifEmpty { m.optString("snippet") }
        return ok(JSONObject().put("id", id).put("from", h["from"] ?: "").put("to", h["to"] ?: "")
            .put("subject", h["subject"] ?: "").put("date", h["date"] ?: "").put("body", body.take(8000)))
    }

    private suspend fun draft(p: JSONObject): JSONObject {
        val raw = mime(p)
        val d = post("$GMAIL/drafts", JSONObject().put("message", JSONObject().put("raw", raw)))
        log("Gmail draft created")
        return ok(JSONObject().put("draft_id", d.optString("id")))
    }

    private suspend fun send(p: JSONObject): JSONObject {
        val raw = mime(p)
        val to = p.optString("to")
        if (!confirm("Send email to $to: \"${p.optString("subject")}\"?")) {
            return err("The user declined on the desk display; the email was not sent.")
        }
        val m = post("$GMAIL/messages/send", JSONObject().put("raw", raw))
        log("Email sent to $to")
        return ok(JSONObject().put("id", m.optString("id")).put("thread_id", m.optString("threadId")).put("sent", true))
    }

    /** RFC 2822 message, base64url-encoded as Gmail's "raw" field. */
    private fun mime(p: JSONObject): String {
        val to = list(p.optString("to")).ifEmpty { throw BadInput("to is required") }
        val subject = p.optString("subject")
        val body = p.optString("body").ifEmpty { throw BadInput("body is required") }
        val cc = list(p.optString("cc"))
        val msg = buildString {
            append("To: ").append(to.joinToString(", ")).append("\r\n")
            if (cc.isNotEmpty()) append("Cc: ").append(cc.joinToString(", ")).append("\r\n")
            append("Subject: ").append(encodeHeader(subject)).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(body.toByteArray()))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(msg.toByteArray())
    }

    data class ThreadMessage(val id: String, val from: String, val snippet: String, val timeMs: Long)

    /** Messages in a thread, oldest first. Used by reply watches. */
    suspend fun threadMessages(threadId: String): List<ThreadMessage> {
        val t = get("$GMAIL/threads/$threadId?format=metadata&metadataHeaders=From")
        val msgs = t.optJSONArray("messages") ?: return emptyList()
        return (0 until msgs.length()).map { i ->
            val m = msgs.getJSONObject(i)
            ThreadMessage(m.optString("id"), headers(m)["from"] ?: "", m.optString("snippet"), m.optString("internalDate").toLongOrNull() ?: 0L)
        }
    }

    /** Newest messages matching a Gmail query: (id, from, subject, snippet, threadId). */
    suspend fun searchBrief(query: String, max: Int = 5): List<JSONObject> {
        val url = "$GMAIL/messages".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("maxResults", max.toString()).build().toString()
        val ids = get(url).optJSONArray("messages") ?: return emptyList()
        return (0 until ids.length()).map { i ->
            val id = ids.getJSONObject(i).getString("id")
            val m = get("$GMAIL/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
            val h = headers(m)
            JSONObject().put("id", id).put("from", h["from"] ?: "").put("subject", h["subject"] ?: "")
                .put("snippet", m.optString("snippet")).put("thread_id", m.optString("threadId"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private class BadInput(message: String) : Exception(message)

    private suspend fun confirm(question: String): Boolean {
        if (!board.connected) throw BadInput("The desk display is not connected, so the user can't confirm. Ask again later.")
        return board.askConfirm(question.take(120), 45) == "yes"
    }

    private suspend fun get(url: String) = call(Request.Builder().url(url))

    private suspend fun post(url: String, body: JSONObject) =
        call(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())))

    private suspend fun call(b: Request.Builder): JSONObject {
        val token = auth.token()
        return withContext(Dispatchers.IO) {
            http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                    throw IllegalStateException("HTTP ${r.code}: $msg")
                }
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            }
        }
    }

    private fun headers(m: JSONObject): Map<String, String> {
        val list = m.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyMap()
        return (0 until list.length()).associate { list.getJSONObject(it).let { h -> h.optString("name").lowercase() to h.optString("value") } }
    }

    /** First text/plain part, depth-first. */
    private fun plainText(part: JSONObject?): String {
        if (part == null) return ""
        if (part.optString("mimeType") == "text/plain") {
            val data = part.optJSONObject("body")?.optString("data").orEmpty()
            if (data.isNotEmpty()) return String(Base64.getUrlDecoder().decode(data))
        }
        val parts = part.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) plainText(parts.getJSONObject(i)).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** ISO 8601 with offset; without one, the phone's time zone. */
    private fun time(s: String): OffsetDateTime = try {
        OffsetDateTime.parse(s)
    } catch (e: DateTimeParseException) {
        try { LocalDateTime.parse(s).atZone(zone).toOffsetDateTime() } catch (e2: DateTimeParseException) {
            throw BadInput("couldn't read the time \"$s\"; use ISO 8601 like 2026-10-06T15:00:00+05:30")
        }
    }

    private fun list(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun encodeHeader(s: String) =
        if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray()) + "?="

    private fun ok(payload: JSONObject) = JSONObject().put("ok", true).put("payload", payload)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun spec(description: String, required: JSONObject = JSONObject(), optional: JSONObject = JSONObject()) =
        JSONObject().put("description", description).put("required", required).put("optional", optional)

    private fun req(vararg p: Pair<String, String>) = params(p.toList(), emptyMap())
    private fun opt(vararg p: Pair<String, String>, type: Map<String, String> = emptyMap()) = params(p.toList(), type)
    private fun params(p: List<Pair<String, String>>, type: Map<String, String>) = JSONObject().apply {
        p.forEach { (k, d) -> put(k, JSONObject().put("type", type[k] ?: "string").put("description", d)) }
    }

    companion object {
        private const val CAL = "https://www.googleapis.com/calendar/v3"
        private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
