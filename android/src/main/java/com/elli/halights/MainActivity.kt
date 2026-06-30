package com.elli.halights

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi

class MainActivity : AppCompatActivity() {

    private val tag = "HALightsMain"
    private lateinit var statusView: TextView

    private var serviceApi: ServiceApi? = null
    private var serviceListener: OnServiceConnectionListener? = null
    private var wearReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            connectWearable()
        } else {
            updateStatus(
                "Permissions not granted:\n${denied.joinToString("\n")}\n\n" +
                    "Grant them in the system dialog or app settings.\n\nHA: ${Config.HA_URL}"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal UI — just a status line
        setContentView(android.R.layout.simple_list_item_1)
        statusView = findViewById<TextView>(android.R.id.text1).apply {
            textSize = 14f
            setPadding(48, 48, 48, 48)
        }

        updateStatus("Checking permissions and starting the bridge...\n\nHA: ${Config.HA_URL}")
        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val missingPermissions = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            connectWearable()
        } else {
            updateStatus("Permissions need to be confirmed...\n\nHA: ${Config.HA_URL}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // ── Mi Wear: find the watch and obtain the DEVICE_MANAGER permission ──────
    // Without this permission a third-party app can't exchange data with the
    // watch, and interconnect on the band shows "phone not connected".
    private fun connectWearable() {
        wearReady = false
        updateStatus("Connecting to the watch service (Mi Fitness / Mi Health)...\n\nHA: ${Config.HA_URL}")
        val sApi = Wearable.getServiceApi(this).also { serviceApi = it }

        val listener = object : OnServiceConnectionListener {
            override fun onServiceConnected() { runOnUiThread { onWearReady() } }
            override fun onServiceDisconnected() {
                runOnUiThread { updateStatus("The watch service disconnected.\n\nHA: ${Config.HA_URL}") }
            }
        }
        serviceListener = listener
        sApi.registerServiceConnectionListener(listener)

        // trigger the binding and catch an explicit error
        sApi.getServiceApiLevel()
            .addOnSuccessListener { runOnUiThread { onWearReady() } }
            .addOnFailureListener { e -> runOnUiThread { showServiceError(e.javaClass.simpleName + ": " + e.message) } }

        // safety timeout — in case the service never comes up (Mi Fitness cold start is slow)
        statusView.postDelayed({ if (!wearReady) showServiceError("30s timeout") }, 30000)
    }

    private fun showServiceError(reason: String) {
        if (wearReady) return
        updateStatus(
            "Failed to connect to the watch service ($reason).\n\n" +
                "Check: the band must be paired with Mi Fitness or Mi Health (Zepp Life will NOT work — the XMS channel works only with those), the watch app is running, and the watch is connected.\n\n" +
                "HA: ${Config.HA_URL}"
        )
    }

    private fun onWearReady() {
        if (wearReady) return
        wearReady = true
        updateStatus("Service connected, looking for the watch...\n\nHA: ${Config.HA_URL}")
        val nodeApi = Wearable.getNodeApi(this)
        val authApi = Wearable.getAuthApi(this)

        nodeApi.getConnectedNodes()
            .addOnSuccessListener { result ->
                Log.i(tag, "getConnectedNodes raw=$result type=${if (result == null) "null" else result.javaClass.name} size=${(result as? List<*>)?.size}")
                @Suppress("UNCHECKED_CAST")
                val nodes = (result as? List<Node>) ?: emptyList()
                if (nodes.isEmpty()) {
                    // getConnectedNodes is empty, but the server-side checks (m1/x3/app-installed)
                    // are removed by the Xposed module, and node 624529513 is actually routable.
                    // No client-side permission request needed — start the bridge directly.
                    Log.i(tag, "no nodes; server checks removed by the module — starting bridge directly")
                    startBridgeService()
                    return@addOnSuccessListener
                }
                checkAppInstalled(nodeApi, authApi, nodes[0])
            }
            .addOnFailureListener { e ->
                Log.e(tag, "getConnectedNodes failed", e)
                updateStatus("Couldn't get the watch list:\n${e.message}\n\nHA: ${Config.HA_URL}")
            }
    }

    // Whether Mi Fitness sees our RPK installed on the watch. If NOT — the app<->watch
    // bond won't form, and that's exactly where "Not Bond" comes from.
    // Test: can Mi Fitness deliver a sendMessage to the band at all.
    // Try several candidate node ids and log the result of each.
    private fun testRouting() {
        updateStatus("Watch not found — testing sendMessage to the band...\n\nHA: ${Config.HA_URL}")
        val ids = listOf(
            "9b6e5fbec1151679ac3a2176d0382fda",
            "624529513",
            "lchz.watch.m67gl"
        )
        val msgApi = Wearable.getMessageApi(this)
        for (id in ids) {
            try {
                msgApi.sendMessage(id, "ping".toByteArray())
                    .addOnSuccessListener { r -> Log.i(tag, "sendMessage[$id] OK result=$r") }
                    .addOnFailureListener { e -> Log.e(tag, "sendMessage[$id] FAIL: ${e.javaClass.simpleName}: ${e.message}", e) }
            } catch (e: Throwable) {
                Log.e(tag, "sendMessage[$id] threw: ${e.message}", e)
            }
        }
    }

    private fun checkAppInstalled(nodeApi: NodeApi, authApi: AuthApi, node: Node) {
        nodeApi.isWearAppInstalled(packageName)
            .addOnSuccessListener { res ->
                val installed = (res as? Boolean) == true
                updateStatus(
                    "Watch: ${node.name}\n" +
                        "RPK on watch: " + (if (installed) "YES" else "NO — bond won't form") + "\n" +
                        "Requesting access...\n\nHA: ${Config.HA_URL}"
                )
                ensurePermission(authApi, node)
            }
            .addOnFailureListener { e ->
                Log.e(tag, "isWearAppInstalled failed", e)
                updateStatus(
                    "isWearAppInstalled failed:\n${e.javaClass.simpleName}: ${e.message}\n\nHA: ${Config.HA_URL}"
                )
                ensurePermission(authApi, node)
            }
    }

    private fun ensurePermission(authApi: AuthApi, node: Node) {
        updateStatus("Watch: ${node.name}\nChecking permission...\n\nHA: ${Config.HA_URL}")
        authApi.checkPermission(node.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                if ((granted as? Boolean) == true) {
                    startBridgeService()
                } else {
                    requestPermission(authApi, node)
                }
            }
            .addOnFailureListener {
                // on some firmwares checkPermission may not work — request directly
                requestPermission(authApi, node)
            }
    }

    private fun requestPermission(authApi: AuthApi, node: Node) {
        updateStatus("Confirm the permission request ON THE WATCH...\n\nHA: ${Config.HA_URL}")
        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener {
                startBridgeService()
            }
            .addOnFailureListener { e ->
                Log.e(tag, "requestPermission failed", e)
                updateStatus(
                    "Permission not granted:\n${e.javaClass.simpleName}: ${e.message}\n" +
                        "Open the request on the watch and tap \"Allow\".\n\nHA: ${Config.HA_URL}"
                )
            }
    }

    private fun startBridgeService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
            updateStatus("HA Lights bridge running\n\nHA: ${Config.HA_URL}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start BridgeService", e)
            updateStatus(
                "Failed to start the bridge:\n${e.javaClass.simpleName}: ${e.message}\n\n" +
                    "HA: ${Config.HA_URL}"
            )
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions
    }

    private fun updateStatus(message: String) {
        Log.i(tag, "STATUS: " + message.replace("\n", " | "))
        statusView.text = message
    }
}
