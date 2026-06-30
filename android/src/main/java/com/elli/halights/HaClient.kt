package com.elli.halights

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HaClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .apply { if (Config.ALLOW_SELF_SIGNED) trustSelfSigned() }
        .build()

    // Accept any SSL cert (self-signed HA). No chain or hostname verification.
    private fun OkHttpClient.Builder.trustSelfSigned() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        sslSocketFactory(ctx.socketFactory, trustAll)
        hostnameVerifier { _, _ -> true }
    }

    private val JSON = "application/json".toMediaType()

    private fun headers() = mapOf(
        "Authorization" to "Bearer ${Config.HA_TOKEN}",
        "Content-Type"  to "application/json",
    )

    private const val TAG = "HALightsBridge"

    // ── GET /api/states/<entity_id> ─────────────────────────────────────────
    private fun getState(entityId: String): JSONObject {
        val req = Request.Builder()
            .url("${Config.HA_URL}/api/states/$entityId")
            .apply { headers().forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("HA ${resp.code} GET states/$entityId: ${body.take(200)}")
            }
            return JSONObject(body)
        }
    }

    // ── Batch state request (in parallel, to fit within the band's timeout) ──
    suspend fun getStates(entityIds: List<String>): JSONArray = coroutineScope {
        val deferred = entityIds.map { id ->
            async(Dispatchers.IO) {
                try {
                    val obj = getState(id)
                    // return only the needed fields to avoid sending extra data to the band
                    JSONObject().apply {
                        put("entity_id",  obj.getString("entity_id"))
                        put("state",      obj.getString("state"))
                        put("attributes", obj.optJSONObject("attributes") ?: JSONObject())
                    }
                } catch (e: Exception) {
                    // if one entity fails — skip it, don't break everything, but log the reason
                    Log.e(TAG, "getState($id) failed", e)
                    null
                }
            }
        }
        val result = JSONArray()
        deferred.awaitAll().forEach { obj -> if (obj != null) result.put(obj) }
        result
    }

    // ── POST /api/services/<domain>/<service> ────────────────────────────────
    fun callService(domain: String, service: String, data: JSONObject) {
        val req = Request.Builder()
            .url("${Config.HA_URL}/api/services/$domain/$service")
            .apply { headers().forEach { (k, v) -> addHeader(k, v) } }
            .post(data.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("HA ${resp.code} POST services/$domain/$service: ${body.take(200)}")
            }
        }
    }

    // ── Send IR via remote.send_command ──────────────────────────────────────
    fun sendIR(entityId: String, command: String) {
        callService("remote", "send_command", JSONObject().apply {
            put("entity_id", entityId)
            put("command",   command)
        })
    }
}
