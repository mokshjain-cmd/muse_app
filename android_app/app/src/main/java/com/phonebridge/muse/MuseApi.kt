package com.phonebridge.muse

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Identity: the board's Bluetooth address, made locally administered. The Muse
 * app checks that the BLE name and node id end in the same six hex digits.
 */
data class MuseIdentity(val mac: String) {
    val suffix: String get() = mac.replace(":", "").takeLast(6)
    val nodeId: String get() = "homelink-$suffix"
    val deviceId: String get() = "hatch-link:$mac"
    val bleName: String get() = "MuseGadget" + suffix.uppercase()

    companion object {
        /** [boardAddr] is "aa:bb:cc:dd:ee:ff" as reported by the board's hello. */
        fun fromBoardAddress(boardAddr: String): MuseIdentity {
            val octets = boardAddr.lowercase().split(":").map { it.toInt(16) }.toMutableList()
            require(octets.size == 6) { "bad board address" }
            octets[0] = (octets[0] and 0xFC) or 0x02 // unicast, locally administered
            return MuseIdentity(octets.joinToString(":") { "%02x".format(it) })
        }
    }
}

/** Device tokens from pairing. Stored in app-private preferences. */
data class MusePairing(
    val accessToken: String,
    val refreshToken: String,
    val apiUrlV2: String,
    val noiseHost: String,
    val savedAt: Long,
)

class MuseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("muse", Context.MODE_PRIVATE)

    /** Defaults to the token built in from local.properties (muse.sdkToken). */
    var sdkToken: String
        get() = prefs.getString("sdk_token", null)?.ifEmpty { null } ?: com.phonebridge.BuildConfig.MUSE_SDK_TOKEN
        set(v) = prefs.edit { putString("sdk_token", v.trim()) }

    var boardAddress: String
        get() = prefs.getString("board_addr", "")!!
        set(v) = prefs.edit { putString("board_addr", v) }

    var pairing: MusePairing?
        get() {
            val access = prefs.getString("access_token", null) ?: return null
            return MusePairing(
                access, prefs.getString("refresh_token", "")!!, prefs.getString("api_url_v2", "")!!,
                prefs.getString("noise_host", "")!!, prefs.getLong("saved_at", 0),
            )
        }
        set(p) = prefs.edit {
            if (p == null) {
                listOf("access_token", "refresh_token", "api_url_v2", "noise_host", "saved_at").forEach(::remove)
            } else {
                putString("access_token", p.accessToken); putString("refresh_token", p.refreshToken)
                putString("api_url_v2", p.apiUrlV2); putString("noise_host", p.noiseHost); putLong("saved_at", p.savedAt)
            }
        }
}

/** REST calls to the Muse device API. Port of muse_api.py. */
object MuseApi {
    const val API_BASE = "https://api.muse.ai"
    const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
    const val USER_AGENT = "musegadget-android/0.2.0 (SiFli phone bridge)"

    data class Vm(val url: String, val token: String, val name: String, val id: String, val isDefault: Boolean)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun root(apiUrlV2: String) = (if (apiUrlV2.startsWith("https://")) apiUrlV2 else API_BASE).trimEnd('/')

    /** Returns (vms, httpStatus or null on transport failure). */
    suspend fun fetchVms(accessToken: String, root: String): Pair<List<Vm>, Int?> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$root/fetch_vms")
            .header("Authorization", "Bearer $accessToken")
            .header("X-API-Version", "1.0.0")
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList<Vm>() to r.code
                val data = JSONObject(r.body?.string().orEmpty())
                if (data.optString("error_title").isNotEmpty() || data.has("backend_error_code")) return@withContext emptyList<Vm>() to r.code
                val list = data.optJSONArray("vm_list") ?: return@withContext emptyList<Vm>() to r.code
                val vms = (0 until list.length()).mapNotNull { i ->
                    val e = list.optJSONObject(i) ?: return@mapNotNull null
                    val url = e.optString("vm_ws_url").ifEmpty { e.optString("vm_url") }
                    val token = e.optString("vm_auth_token")
                    if (url.isEmpty() || token.isEmpty()) null
                    else Vm(url, token, e.optString("vm_name"), e.optString("vm_id"), e.optBoolean("default", false))
                }
                vms to r.code
            }
        } catch (e: IOException) {
            emptyList<Vm>() to null
        } catch (e: org.json.JSONException) {
            emptyList<Vm>() to 200
        }
    }

    /**
     * Rotates the device token pair. Only the refresh token is presented, with
     * the hatch_refresh: prefix (never doubled). Returns (tokens, status).
     */
    suspend fun refresh(refreshToken: String, deviceId: String, root: String, sdkToken: String?): Pair<Pair<String, String>?, Int?> =
        withContext(Dispatchers.IO) {
            val raw = refreshToken.substringAfterLast(":")
            val body = JSONObject().put("device_id", deviceId)
            if (!sdkToken.isNullOrEmpty()) body.put("sdk_token", sdkToken)
            val req = Request.Builder().url("$root/device_token/refresh")
                .header("Authorization", "Bearer hatch_refresh:$raw")
                .header("User-Agent", USER_AGENT)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null to r.code
                    var data = JSONObject(r.body?.string().orEmpty())
                    data.optJSONObject("payload")?.let { data = it }
                    val a = data.optString("access_token")
                    val rt = data.optString("refresh_token")
                    (if (a.isNotEmpty() && rt.isNotEmpty()) a to rt else null) to 200
                }
            } catch (e: IOException) {
                null to null
            } catch (e: org.json.JSONException) {
                null to 200
            }
        }
}
