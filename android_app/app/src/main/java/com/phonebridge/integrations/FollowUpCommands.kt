package com.phonebridge.integrations

import android.content.Context
import androidx.core.content.edit
import com.phonebridge.google.GoogleCommands
import com.phonebridge.muse.CommandProvider
import com.phonebridge.muse.Specs
import com.phonebridge.muse.Specs.P
import com.phonebridge.muse.Specs.need
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Things that happen later, so the user can say "let me know when he responds"
 * or "every morning at 8, ...":
 *  - watches: the phone checks for an email reply and tells the user (board +
 *    voice) and Muse when one arrives;
 *  - routines: at a set time the phone hands an instruction to Muse, which does
 *    the work with its other commands.
 * Both persist across app restarts.
 */
class FollowUpCommands(
    context: Context,
    private val scope: CoroutineScope,
    private val google: GoogleCommands,
    /** Tell the user now (board banner, voice) and pass [forMuse] to Muse so it can follow up. */
    private val report: (title: String, text: String, forMuse: String) -> Unit,
    /** Hand an instruction to Muse as if the user said it. */
    private val instruct: (String) -> Unit,
    private val log: (String) -> Unit,
) : CommandProvider {

    private val prefs = context.applicationContext.getSharedPreferences("followups", Context.MODE_PRIVATE)

    init {
        scope.launch { while (true) { runCatching { checkWatches() }; delay(WATCH_POLL_MS) } }
        scope.launch { while (true) { runCatching { checkRoutines() }; delay(30_000) } }
    }

    override val specs: JSONObject
        get() = JSONObject()
            .also { if (googleReady()) addWatchSpecs(it) }
            .put("routine.create", ROUTINE_CREATE)
            .put("routine.list", Specs.spec("List scheduled routines."))
            .put("routine.delete", Specs.spec("Delete a routine.", listOf(P("id", "Routine id."))))

    /** Reply watches read Gmail through Phone Bridge, so they need its Google sign-in. */
    private fun googleReady() = google.specs.length() > 0

    private fun addWatchSpecs(o: JSONObject) = o
            .put("watch.email_reply", Specs.spec(
                "Watch for a reply to an email and tell the user on the desk display (and you) as soon as one arrives. " +
                    "Give the thread_id from gmail.send, or a sender address to wait for.",
                optional = listOf(P("thread_id", "Gmail thread id (from gmail.send)."), P("from", "Sender email address to wait for."),
                    P("about", "Short description shown to the user, e.g. 'Cypher about the launch'."),
                    P("hours", "Give up after this many hours. Default 48.", "integer")),
            ))
            .put("watch.list", Specs.spec("List active watches."))
            .put("watch.cancel", Specs.spec("Cancel a watch.", listOf(P("id", "Watch id."))))

    private val ROUTINE_CREATE = Specs.spec(
                "Schedule a routine: at the given local time the instruction is handed back to you as if the user said it " +
                    "(e.g. 'Read me today's calendar and unread email from my manager').",
                listOf(P("time", "Local time HH:mm, 24-hour."), P("instruction", "What to do then.")),
                listOf(P("days", "Comma-separated days (mon,tue,...) or 'daily' (default) or 'once'."), P("name", "Short name.")),
            )

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("watch.") && !command.startsWith("routine.")) return null
        return try {
            when (command) {
                "watch.email_reply" -> addWatch(params)
                "watch.list" -> Specs.ok(JSONObject().put("watches", load("watches")))
                "watch.cancel" -> { remove("watches", params.need("id")); Specs.ok() }
                "routine.create" -> addRoutine(params)
                "routine.list" -> Specs.ok(JSONObject().put("routines", load("routines")))
                "routine.delete" -> { remove("routines", params.need("id")); Specs.ok() }
                else -> Specs.err("unknown command: $command")
            }
        } catch (e: Specs.Fail) {
            Specs.err(e.message!!)
        }
    }

    // ------------------------------------------------------------------ watches

    private suspend fun addWatch(p: JSONObject): JSONObject {
        val thread = p.optString("thread_id")
        val from = p.optString("from")
        if (thread.isEmpty() && from.isEmpty()) throw Specs.Fail("Give thread_id or from.")
        val w = JSONObject().put("id", UUID.randomUUID().toString().take(8))
            .put("about", p.optString("about").ifEmpty { from.ifEmpty { "your email" } })
            .put("thread_id", thread).put("from", from)
            .put("since", System.currentTimeMillis())
            .put("until", System.currentTimeMillis() + p.optInt("hours", 48).coerceIn(1, 24 * 14) * 3_600_000L)
        if (thread.isNotEmpty()) w.put("seen", runCatching { google.threadMessages(thread).size }.getOrDefault(1))
        save("watches", load("watches").put(w))
        log("Watching for a reply: ${w.getString("about")}")
        return Specs.ok(JSONObject().put("id", w.getString("id")).put("checks_every_minutes", WATCH_POLL_MS / 60_000))
    }

    private suspend fun checkWatches() {
        val watches = load("watches")
        if (watches.length() == 0) return
        val keep = JSONArray()
        for (i in 0 until watches.length()) {
            val w = watches.getJSONObject(i)
            if (System.currentTimeMillis() > w.getLong("until")) { log("Watch expired: ${w.optString("about")}"); continue }
            val hit = runCatching { findReply(w) }.getOrNull()
            if (hit == null) { keep.put(w); continue }
            val (who, snippet) = hit
            val about = w.optString("about")
            report(
                "Reply from $who",
                snippet,
                "The reply the user was waiting for ($about) arrived. $who wrote: \"$snippet\". " +
                    "Tell the user briefly on the desk display.",
            )
            log("Reply arrived: ${w.optString("about")}")
        }
        save("watches", keep)
    }

    /** (sender, snippet) of the first new reply, or null. */
    private suspend fun findReply(w: JSONObject): Pair<String, String>? {
        val thread = w.optString("thread_id")
        if (thread.isNotEmpty()) {
            val msgs = google.threadMessages(thread)
            val seen = w.optInt("seen", 1)
            val fresh = msgs.drop(seen).firstOrNull() ?: return null
            return fresh.from to fresh.snippet
        }
        val after = w.getLong("since") / 1000
        val m = google.searchBrief("from:${w.optString("from")} after:$after", 1).firstOrNull() ?: return null
        return m.optString("from") to m.optString("snippet")
    }

    // ------------------------------------------------------------------ routines

    private fun addRoutine(p: JSONObject): JSONObject {
        val time = runCatching { LocalTime.parse(p.need("time")) }.getOrElse { throw Specs.Fail("time must be HH:mm") }
        val days = p.optString("days", "daily").lowercase().ifEmpty { "daily" }
        val r = JSONObject().put("id", UUID.randomUUID().toString().take(8))
            .put("name", p.optString("name").ifEmpty { p.need("instruction").take(40) })
            .put("time", time.toString()).put("days", days).put("instruction", p.need("instruction"))
            .put("last_run", "")
        save("routines", load("routines").put(r))
        log("Routine scheduled: ${r.getString("name")} at $time ($days)")
        return Specs.ok(JSONObject().put("id", r.getString("id")))
    }

    private fun checkRoutines() {
        val routines = load("routines")
        if (routines.length() == 0) return
        val now = LocalDateTime.now()
        val today = LocalDate.now().toString()
        val keep = JSONArray()
        for (i in 0 until routines.length()) {
            val r = routines.getJSONObject(i)
            val at = LocalTime.parse(r.getString("time"))
            val due = r.optString("last_run") != today && dayMatches(r.getString("days"), now.dayOfWeek) &&
                !now.toLocalTime().isBefore(at) && now.toLocalTime().isBefore(at.plusMinutes(10))
            if (due) {
                log("Routine: ${r.optString("name")}")
                instruct(r.getString("instruction"))
                r.put("last_run", today)
                if (r.getString("days") == "once") continue
            }
            keep.put(r)
        }
        save("routines", keep)
    }

    private fun dayMatches(days: String, d: DayOfWeek): Boolean {
        if (days == "daily" || days == "once") return true
        val short = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
        return days.split(',').any { it.trim().startsWith(short) }
    }

    // ------------------------------------------------------------------ storage

    @Synchronized private fun load(key: String) = JSONArray(prefs.getString(key, "[]"))
    @Synchronized private fun save(key: String, a: JSONArray) = prefs.edit { putString(key, a.toString()) }

    @Synchronized
    private fun remove(key: String, id: String) {
        val a = load(key)
        save(key, JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") != id }))
    }

    companion object {
        private const val WATCH_POLL_MS = 2 * 60_000L
    }
}
