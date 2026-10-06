package com.phonebridge.integrations

import com.phonebridge.Settings
import com.phonebridge.muse.CommandProvider
import com.phonebridge.muse.Specs
import com.phonebridge.muse.Specs.P
import com.phonebridge.muse.Specs.need
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asana through its REST API with a personal access token
 * (app.asana.com > My settings > Apps > Developer console > Personal access token).
 */
class AsanaCommands(private val settings: Settings, private val log: (String) -> Unit) : CommandProvider {

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var workspace: String? = null
    private var me: String? = null

    override val specs: JSONObject
        get() = if (settings.asanaToken.isEmpty()) JSONObject() else JSONObject()
            .put("asana.create_task", Specs.spec(
                "Create a task in the user's Asana. Assigned to the user unless an assignee email is given. Returns the task id and link.",
                listOf(P("name", "Task title.")),
                listOf(P("notes", "Details."), P("due_on", "Due date, YYYY-MM-DD."),
                    P("project", "Project name to add it to (see asana.list_projects)."),
                    P("assignee", "Assignee email, or 'me'. Default me.")),
            ))
            .put("asana.list_tasks", Specs.spec(
                "List the user's open Asana tasks, optionally filtered by text. Returns id, name, due date and link.",
                optional = listOf(P("text", "Only tasks whose name contains this."), P("include_completed", "Also list completed tasks.", "boolean")),
            ))
            .put("asana.complete_task", Specs.spec("Mark an Asana task complete.", listOf(P("task_id", "Task id."))))
            .put("asana.add_comment", Specs.spec("Add a comment to an Asana task.", listOf(P("task_id", "Task id."), P("text", "Comment."))))
            .put("asana.list_projects", Specs.spec("List projects in the user's Asana workspace."))

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("asana.")) return null
        if (settings.asanaToken.isEmpty()) return Specs.err("Asana isn't set up in Phone Bridge settings.")
        return try {
            when (command) {
                "asana.create_task" -> createTask(params)
                "asana.list_tasks" -> listTasks(params)
                "asana.complete_task" -> {
                    call("PUT", "/tasks/${params.need("task_id")}", JSONObject().put("data", JSONObject().put("completed", true)))
                    Specs.ok(JSONObject().put("completed", true))
                }
                "asana.add_comment" -> {
                    call("POST", "/tasks/${params.need("task_id")}/stories", JSONObject().put("data", JSONObject().put("text", params.need("text"))))
                    Specs.ok(JSONObject().put("commented", true))
                }
                "asana.list_projects" -> Specs.ok(JSONObject().put("projects", projects()))
                else -> Specs.err("unknown command: $command")
            }
        } catch (e: Specs.Fail) {
            Specs.err(e.message!!)
        } catch (e: Exception) {
            log("$command failed: ${e.message}")
            Specs.err("Asana request failed: ${e.message}")
        }
    }

    private suspend fun createTask(p: JSONObject): JSONObject {
        val ws = workspace()
        val data = JSONObject().put("name", p.need("name")).put("workspace", ws)
            .put("assignee", p.optString("assignee").ifEmpty { "me" })
        p.optString("notes").takeIf { it.isNotEmpty() }?.let { data.put("notes", it) }
        p.optString("due_on").takeIf { it.isNotEmpty() }?.let { data.put("due_on", it) }
        p.optString("project").takeIf { it.isNotEmpty() }?.let { name ->
            val match = (0 until projects().length()).map { projects().getJSONObject(it) }
                .firstOrNull { it.getString("name").equals(name, ignoreCase = true) }
                ?: throw Specs.Fail("No Asana project named \"$name\". Use asana.list_projects.")
            data.put("projects", JSONArray().put(match.getString("id")))
        }
        val t = call("POST", "/tasks", JSONObject().put("data", data)).getJSONObject("data")
        log("Asana task created: ${t.optString("name")}")
        return Specs.ok(JSONObject().put("task_id", t.optString("gid")).put("link", t.optString("permalink_url")))
    }

    private suspend fun listTasks(p: JSONObject): JSONObject {
        val url = "$BASE/tasks".toHttpUrl().newBuilder()
            .addQueryParameter("assignee", "me").addQueryParameter("workspace", workspace())
            .addQueryParameter("opt_fields", "name,due_on,completed,permalink_url").addQueryParameter("limit", "100")
        if (!p.optBoolean("include_completed")) url.addQueryParameter("completed_since", "now")
        val items = get(url.build().toString()).optJSONArray("data") ?: JSONArray()
        val text = p.optString("text").lowercase()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val t = items.getJSONObject(i)
            if (text.isNotEmpty() && text !in t.optString("name").lowercase()) continue
            out.put(JSONObject().put("task_id", t.optString("gid")).put("name", t.optString("name"))
                .put("due_on", t.optString("due_on")).put("completed", t.optBoolean("completed")).put("link", t.optString("permalink_url")))
            if (out.length() >= 30) break
        }
        return Specs.ok(JSONObject().put("tasks", out))
    }

    private var projectCache: JSONArray? = null

    private suspend fun projects(): JSONArray = projectCache ?: run {
        val items = get("$BASE/projects?workspace=${workspace()}&archived=false&limit=100").optJSONArray("data") ?: JSONArray()
        JSONArray((0 until items.length()).map {
            val pr = items.getJSONObject(it)
            JSONObject().put("id", pr.optString("gid")).put("name", pr.optString("name"))
        }).also { projectCache = it }
    }

    private suspend fun workspace(): String = workspace ?: run {
        val user = get("$BASE/users/me").getJSONObject("data")
        me = user.optString("gid")
        val ws = user.optJSONArray("workspaces")?.optJSONObject(0)?.optString("gid") ?: throw Specs.Fail("No Asana workspace found.")
        ws.also { workspace = it }
    }

    private suspend fun get(url: String) = send(Request.Builder().url(url))

    private suspend fun call(method: String, path: String, body: JSONObject) =
        send(Request.Builder().url(BASE + path).method(method, body.toString().toRequestBody("application/json".toMediaType())))

    private suspend fun send(b: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(b.header("Authorization", "Bearer ${settings.asanaToken}").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONArray("errors").getJSONObject(0).getString("message") }.getOrDefault(text.take(200))
                throw IllegalStateException("HTTP ${r.code}: $msg")
            }
            if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    companion object {
        private const val BASE = "https://app.asana.com/api/1.0"
    }
}
