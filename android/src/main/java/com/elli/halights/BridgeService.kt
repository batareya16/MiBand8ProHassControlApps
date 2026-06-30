package com.elli.halights

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.*
import org.json.JSONObject

class BridgeService : Service() {

    private val TAG = "HALightsBridge"
    private val CHANNEL_ID = "ha_lights_bridge"
    // XMS MessageApi works by nodeId (NOT by path!) — see the Mi Wearable v1.4 spec.
    // addListener/sendMessage/removeListener take the band's node id.
    private val NODE_ID = "624529513"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var messageApi: MessageApi
    private lateinit var nodeApi: NodeApi
    private var listenerRegistered = false

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        try {
            initWearable()
        } catch (e: Exception) {
            Log.e(TAG, "Bridge initialization failed", e)
            updateNotification("Bridge init failed: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // restart if the system kills us
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (listenerRegistered) {
            messageApi.removeListener(NODE_ID)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Interconnect ─────────────────────────────────────────────────────────

    private fun initWearable() {
        messageApi = Wearable.getMessageApi(this)
        nodeApi = Wearable.getNodeApi(this)

        messageApi.addListener(NODE_ID, object : OnMessageReceivedListener {
            override fun onMessageReceived(nodeId: String, data: ByteArray) {
                val rawData = data.toString(Charsets.UTF_8)
                Log.d(TAG, "← watch[$nodeId]: $rawData")
                scope.launch { handleMessage(rawData) }
            }
        })
            .addOnSuccessListener {
                listenerRegistered = true
                Log.d(TAG, "listener registered for node $NODE_ID")
                openChannel()
            }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to register message listener", e) }

        Log.d(TAG, "Wearable bridge started for node: $NODE_ID")
    }

    // Bring up the paired session from the PHONE side: launch the watch app
    // (launchWearApp) and send a few "hello"s to wake the interconnect channel —
    // the watch sees "401 not connected" until the phone initiates the link.
    private fun openChannel() {
        try {
            nodeApi.launchWearApp(NODE_ID, packageName)
                .addOnSuccessListener { Log.d(TAG, "launchWearApp OK") }
                .addOnFailureListener { e -> Log.e(TAG, "launchWearApp FAIL: ${e.javaClass.simpleName}: ${e.message}") }
        } catch (e: Throwable) {
            Log.e(TAG, "launchWearApp threw: ${e.message}")
        }
        // a few hello pings to the node to wake the channel
        scope.launch {
            repeat(8) { i ->
                try {
                    val hello = "{\"hello\":$i}".toByteArray(Charsets.UTF_8)
                    messageApi.sendMessage(NODE_ID, hello)
                        .addOnSuccessListener { Log.d(TAG, "hello[$i] -> $NODE_ID OK") }
                        .addOnFailureListener { e -> Log.e(TAG, "hello[$i] FAIL: ${e.javaClass.simpleName}: ${e.message}") }
                } catch (e: Throwable) {
                    Log.e(TAG, "hello[$i] threw: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    // ── Message handler ──────────────────────────────────────────────────────

    private suspend fun handleMessage(rawData: String) {
        val req = try { JSONObject(rawData) } catch (e: Exception) {
            Log.e(TAG, "Bad JSON: $rawData"); return
        }

        val id      = req.optString("id", "")
        val action  = req.optString("action", "")
        val payload = req.optJSONObject("payload") ?: JSONObject()
        val src     = req.optString("src", "")   // requesting watch app package (coordinator routing)

        val response = JSONObject()
        response.put("id", id)

        try {
            when (action) {

                "get_states" -> {
                    val ids = payload.getJSONArray("entity_ids")
                    val list = mutableListOf<String>()
                    for (i in 0 until ids.length()) list.add(ids.getString(i))
                    val states = HaClient.getStates(list)
                    response.put("ok",   true)
                    response.put("data", states)
                }

                "call_service" -> {
                    val domain  = payload.getString("domain")
                    val service = payload.getString("service")
                    val data    = payload.optJSONObject("data") ?: JSONObject()
                    HaClient.callService(domain, service, data)
                    response.put("ok",   true)
                    response.put("data", JSONObject())
                }

                "send_ir" -> {
                    val command  = payload.getString("command")
                    val entityId = "remote.chuangmi_v2_9dae_ir"
                    HaClient.sendIR(entityId, command)
                    response.put("ok",   true)
                    response.put("data", JSONObject())
                }

                else -> {
                    response.put("ok",    false)
                    response.put("error", "Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $action", e)
            response.put("ok",    false)
            response.put("error", e.message ?: "Error")
        }

        val replyJson = response.toString()
        Log.d(TAG, "→ watch[$src]: $replyJson")
        sendToWatch(replyJson, src)
    }

    private fun sendToWatch(message: String, target: String = "") {
        // The MiWearBridge hook reads a "@w:<watchPackage>\n" header and routes the reply to
        // that watch app, so one coordinator (this app) can serve several watch apps.
        // No header -> the band delivers to the coordinator's own watch app (default).
        val bytes = (if (target.isNotEmpty()) "@w:$target\n$message" else message)
            .toByteArray(Charsets.UTF_8)
        messageApi.sendMessage(NODE_ID, bytes)
            .addOnSuccessListener { Log.d(TAG, "→ sent to ${if (target.isNotEmpty()) target else NODE_ID} OK") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to send reply", e) }
    }

    // ── Notification (foreground service requirement) ────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HA Lights Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps connection to Home Assistant" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HA Lights")
            .setContentText("Connected to Home Assistant")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HA Lights")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
