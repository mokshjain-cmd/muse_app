package com.phonebridge.integrations

import com.phonebridge.Settings
import com.phonebridge.muse.BoardPort
import com.phonebridge.muse.CommandProvider
import com.phonebridge.muse.Specs
import com.phonebridge.muse.Specs.P
import com.phonebridge.muse.Specs.need
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Home Assistant (REST API with a long-lived access token) and MQTT publishing.
 * The phone is on the home Wi-Fi, so it reaches these directly.
 */
class HomeCommands(
    private val settings: Settings,
    private val board: BoardPort,
    private val log: (String) -> Unit,
) : CommandProvider {

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private var mqtt: MqttClient? = null

    private val haReady get() = settings.haUrl.isNotEmpty() && settings.haToken.isNotEmpty()
    private val mqttReady get() = settings.mqttUrl.isNotEmpty()

    override val specs: JSONObject
        get() {
            val o = JSONObject()
            if (haReady) {
                o.put("home.states", Specs.spec(
                    "List Home Assistant devices and their states (lights, switches, sensors...). Use the entity_id with home.call_service.",
                    optional = listOf(P("domain", "Only this domain, e.g. light, switch, sensor, climate."),
                        P("search", "Only entities whose name or id contains this.")),
                ))
                o.put("home.call_service", Specs.spec(
                    "Call a Home Assistant service, e.g. domain=light service=turn_off entity_id=light.bedroom, or " +
                        "domain=climate service=set_temperature data={\"temperature\":24}. Locks and alarms are confirmed on the desk display first.",
                    listOf(P("domain", "Service domain, e.g. light."), P("service", "Service, e.g. turn_on.")),
                    listOf(P("entity_id", "Target entity id(s), comma-separated."), P("data", "Extra service data as a JSON object string.")),
                ))
            }
            if (mqttReady) {
                o.put("mqtt.publish", Specs.spec(
                    "Publish a message to the user's MQTT broker (for DIY devices, Tasmota, Zigbee2MQTT...).",
                    listOf(P("topic", "MQTT topic."), P("payload", "Message payload (text or JSON).")),
                    listOf(P("retain", "Retain the message.", "boolean")),
                ))
            }
            return o
        }

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("home.") && !command.startsWith("mqtt.")) return null
        return try {
            when (command) {
                "home.states" -> states(params)
                "home.call_service" -> callService(params)
                "mqtt.publish" -> publish(params)
                else -> Specs.err("unknown command: $command")
            }
        } catch (e: Specs.Fail) {
            Specs.err(e.message!!)
        } catch (e: Exception) {
            log("$command failed: ${e.message}")
            Specs.err("$command failed: ${e.message}")
        }
    }

    private suspend fun states(p: JSONObject): JSONObject {
        if (!haReady) throw Specs.Fail("Home Assistant isn't set up in Phone Bridge settings.")
        val all = JSONArray(ha("GET", "/api/states", null))
        val domain = p.optString("domain")
        val search = p.optString("search").lowercase()
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val e = all.getJSONObject(i)
            val id = e.optString("entity_id")
            val name = e.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
            if (domain.isNotEmpty() && !id.startsWith("$domain.")) continue
            if (search.isNotEmpty() && search !in id.lowercase() && search !in name.lowercase()) continue
            out.put(JSONObject().put("entity_id", id).put("name", name).put("state", e.optString("state")))
            if (out.length() >= 60) break
        }
        return Specs.ok(JSONObject().put("entities", out))
    }

    private suspend fun callService(p: JSONObject): JSONObject {
        if (!haReady) throw Specs.Fail("Home Assistant isn't set up in Phone Bridge settings.")
        val domain = p.need("domain")
        val service = p.need("service")
        val body = p.optString("data").takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrElse { throw Specs.Fail("data must be a JSON object") } } ?: JSONObject()
        p.optString("entity_id").takeIf { it.isNotEmpty() }?.let { ids ->
            body.put("entity_id", JSONArray(ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        }
        if (domain in setOf("lock", "alarm_control_panel")) {
            if (!board.connected) throw Specs.Fail("The desk display isn't connected, so the user can't confirm.")
            if (board.askConfirm("$service ${p.optString("entity_id")}?".take(100), 45) != "yes") {
                return Specs.err("The user declined on the desk display.")
            }
        }
        ha("POST", "/api/services/$domain/$service", body.toString())
        log("Home Assistant: $domain.$service ${p.optString("entity_id")}")
        return Specs.ok(JSONObject().put("done", true))
    }

    private suspend fun ha(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(settings.haUrl + path).header("Authorization", "Bearer ${settings.haToken}")
        if (body != null) b.method(method, body.toRequestBody("application/json".toMediaType())) else b.get()
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${text.take(200)}")
            text
        }
    }

    private suspend fun publish(p: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!mqttReady) throw Specs.Fail("MQTT isn't set up in Phone Bridge settings.")
        val client = mqtt?.takeIf { it.isConnected } ?: MqttClient(settings.mqttUrl, "phonebridge-" + System.currentTimeMillis() % 100000, MemoryPersistence()).also {
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                if (settings.mqttUser.isNotEmpty()) userName = settings.mqttUser
                if (settings.mqttPassword.isNotEmpty()) password = settings.mqttPassword.toCharArray()
            }
            it.connect(opts)
            mqtt = it
        }
        val msg = MqttMessage(p.need("payload").toByteArray()).apply { isRetained = p.optBoolean("retain") }
        client.publish(p.need("topic"), msg)
        log("MQTT: ${p.optString("topic")}")
        Specs.ok(JSONObject().put("published", true))
    }
}
