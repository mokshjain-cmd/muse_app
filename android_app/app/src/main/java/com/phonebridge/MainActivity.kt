package com.phonebridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonebridge.databinding.ActivityMainBinding
import com.phonebridge.muse.MuseClient
import com.phonebridge.voice.Speech
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private val OPTIONAL_PERMISSIONS = setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)
    }

    private lateinit var b: ActivityMainBinding

    private val permissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.CAMERA) // optional: camera.read_text / camera.scan_code
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val bluetoothOk = result.filterKeys { it !in OPTIONAL_PERMISSIONS }.values.all { it }
            if (bluetoothOk) startConnect() else Toast.makeText(this, R.string.bt_needed, Toast.LENGTH_LONG).show()
        }

    private val googleLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (!Bridge.google.finish(res.data)) Toast.makeText(this, getString(R.string.google_failed, "cancelled"), Toast.LENGTH_LONG).show()
            renderGoogle()
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Bridge.isBluetoothOn) BridgeService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keep content clear of the status and navigation bars (edge-to-edge on Android 15+).
        val pad = b.content.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.content.updatePadding(top = pad + bars.top, bottom = pad + bars.bottom)
            insets
        }

        b.cityInput.setText(Bridge.city)
        Bridge.settings.let { st ->
            b.asanaTokenInput.setText(st.asanaToken)
            b.haUrlInput.setText(st.haUrl)
            b.haTokenInput.setText(st.haToken)
            b.mqttUrlInput.setText(st.mqttUrl)
            b.mqttUserInput.setText(st.mqttUser)
            b.mqttPasswordInput.setText(st.mqttPassword)
        }
        b.sdkTokenInput.setText(Bridge.museStore.sdkToken)
        b.speechGroup.check(when (Bridge.speechOutput) {
            Speech.Output.BOARD -> R.id.speechBoard
            Speech.Output.PHONE -> R.id.speechPhone
            Speech.Output.OFF -> R.id.speechOff
        })
        b.speechGroup.setOnCheckedChangeListener { _, id ->
            Bridge.speechOutput = when (id) {
                R.id.speechPhone -> Speech.Output.PHONE
                R.id.speechOff -> Speech.Output.OFF
                else -> Speech.Output.BOARD
            }
        }

        b.connectButton.setOnClickListener {
            if (Bridge.linkUi.value.state == BleLink.State.CONNECTED) BridgeService.stop(this) else startConnect()
        }
        b.askButton.setOnClickListener { askMuse() }
        b.askInput.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) askMuse() } }
        b.pairButton.setOnClickListener { togglePairing() }
        b.unpairButton.setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.unpair_confirm)
                .setPositiveButton(R.string.unpair) { _, _ -> Bridge.muse.unpair() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        b.googleButton.setOnClickListener { toggleGoogle() }
        renderGoogle()
        b.sendButton.setOnClickListener { sendTypedMessage() }
        b.messageInput.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) sendTypedMessage() } }
        b.timeButton.setOnClickListener { Bridge.pushTime() }
        b.weatherButton.setOnClickListener { Bridge.pushWeather() }
        b.saveButton.setOnClickListener {
            Bridge.city = b.cityInput.text.toString()
            Bridge.settings.let { st ->
                st.asanaToken = b.asanaTokenInput.text.toString()
                st.haUrl = b.haUrlInput.text.toString()
                st.haToken = b.haTokenInput.text.toString()
                st.mqttUrl = b.mqttUrlInput.text.toString()
                st.mqttUser = b.mqttUserInput.text.toString()
                st.mqttPassword = b.mqttPasswordInput.text.toString()
            }
            b.deviceTokenInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Bridge.museStore.pairing = com.phonebridge.muse.MusePairing(it, "", "", "", System.currentTimeMillis())
                b.deviceTokenInput.text?.clear()
            }
            Bridge.muse.reregister()   // Muse sees the new command list on reconnect
            Bridge.muse.startLoop()
            Bridge.museStore.sdkToken = b.sdkTokenInput.text.toString()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        // Connect on open once a board has been used before and permissions are granted.
        if (Bridge.knownBoard != null && Bridge.linkUi.value.state == BleLink.State.IDLE &&
            permissions.filter { it !in OPTIONAL_PERMISSIONS }
                .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } &&
            Bridge.isBluetoothOn
        ) BridgeService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Bridge.linkUi.combine(Bridge.muse.status) { l, m -> l to m }.collect { (l, m) -> render(l, m) } }
                launch { Bridge.log.collect { b.logText.text = it.joinToString("\n") } }
            }
        }
    }

    private fun render(ui: Bridge.LinkUi, muse: MuseClient.Status) {
        val connected = ui.state == BleLink.State.CONNECTED
        val busy = ui.state == BleLink.State.SCANNING || ui.state == BleLink.State.CONNECTING

        b.statusText.text = when (ui.state) {
            BleLink.State.IDLE -> getString(R.string.state_idle)
            BleLink.State.SCANNING -> getString(R.string.state_scanning)
            BleLink.State.CONNECTING -> getString(R.string.state_connecting)
            BleLink.State.CONNECTED -> getString(R.string.state_connected, ui.deviceName)
        }
        tint(b.statusDot, if (connected) R.color.ok else R.color.off)
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.connectButton.isEnabled = !busy
        b.connectButton.setText(if (connected) R.string.disconnect else R.string.connect)
        listOf(b.sendButton, b.timeButton, b.weatherButton).forEach { it.isEnabled = connected }

        b.museStatus.text = when {
            !connected && muse != MuseClient.Status.CONNECTED -> getString(R.string.muse_no_board)
            muse == MuseClient.Status.PAIRING -> getString(R.string.muse_pairing, Bridge.muse.identity?.bleName ?: "")
            muse == MuseClient.Status.CONNECTED -> getString(R.string.muse_connected)
            muse == MuseClient.Status.CONNECTING -> getString(R.string.muse_connecting)
            muse == MuseClient.Status.OFFLINE -> getString(R.string.muse_offline)
            else -> getString(R.string.muse_not_paired)
        }
        tint(b.museDot, when (muse) {
            MuseClient.Status.CONNECTED -> R.color.ok
            MuseClient.Status.PAIRING, MuseClient.Status.CONNECTING -> R.color.accent
            else -> R.color.off
        })
        b.askButton.isEnabled = muse == MuseClient.Status.CONNECTED
        b.pairButton.isEnabled = connected
        b.pairButton.setText(if (muse == MuseClient.Status.PAIRING) R.string.cancel_pairing else R.string.pair)
        b.unpairButton.isEnabled = Bridge.museStore.pairing != null
    }

    private fun renderGoogle() {
        val on = Bridge.google.connected
        b.googleStatus.setText(if (on) R.string.google_on else R.string.google_off)
        b.googleButton.setText(if (on) R.string.google_disconnect else R.string.google_sign_in)
    }

    private fun toggleGoogle() {
        if (Bridge.google.connected) { Bridge.google.disconnect(); renderGoogle(); return }
        lifecycleScope.launch {
            try {
                val consent = Bridge.google.begin()
                if (consent != null) googleLauncher.launch(IntentSenderRequest.Builder(consent).build())
                else renderGoogle()
            } catch (e: Exception) {
                // ApiException 10 (DEVELOPER_ERROR) means the Android OAuth client's package or SHA-1 doesn't match.
                Toast.makeText(this@MainActivity, getString(R.string.google_failed, e.message ?: "error"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tint(v: View, color: Int) =
        ViewCompat.setBackgroundTintList(v, ContextCompat.getColorStateList(this, color))

    private fun togglePairing() {
        if (Bridge.muse.status.value == MuseClient.Status.PAIRING) { Bridge.cancelPairing(); return }
        val token = b.sdkTokenInput.text.toString().trim()
        if (token.isEmpty()) { Toast.makeText(this, R.string.no_sdk_token, Toast.LENGTH_LONG).show(); return }
        Bridge.museStore.sdkToken = token
        Bridge.startPairing()
    }

    private fun askMuse() {
        val text = b.askInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        Bridge.askMuse(text)
        b.askInput.text?.clear()
    }

    private fun sendTypedMessage() {
        val text = b.messageInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        Bridge.sendMessage("From phone", text)
        b.messageInput.text?.clear()
    }

    private fun startConnect() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isNotEmpty() -> permissionLauncher.launch(missing.toTypedArray())
            !Bridge.isBluetoothOn -> enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> BridgeService.start(this)
        }
    }
}
