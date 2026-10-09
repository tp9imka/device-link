package dev.devicelink

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.devicelink.model.*
import kotlinx.coroutines.*

/** Explicit manual two-device probe. It never approves pairing or reads a background clipboard. */
class RelayDeviceProbe : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val url = requireNotNull(arguments.getString("relayUrl"))
            val peerName = requireNotNull(arguments.getString("peerName"))
            val application = targetContext.applicationContext as DeviceLinkApplication
            runBlocking {
                withContext(Dispatchers.Main) {
                    check(application.internetLink.configure(url, "", true))
                    check(application.controller.state.value.trustedPeers.any { it.name == peerName })
                    application.controller.startSession(15)
                }
                await("nearby discovery", 60_000) { application.controller.state.value.nearbyPeers.any { it.name == peerName } }
                withContext(Dispatchers.Main) {
                    application.controller.connect(application.controller.state.value.nearbyPeers.first { it.name == peerName }.id)
                }
                await("remembered authenticated nearby connection", 60_000) { application.controller.state.value.phase == LinkPhase.CONNECTED }
                await("verified relay key exchange", 30_000) { application.internetLink.state.value.peers.any { it.name == peerName } }
                withContext(Dispatchers.Main) { application.controller.stopSession(); application.internetLink.start(15) }
                await("relay ready", 30_000) { application.internetLink.state.value.status == InternetStatus.READY }
                val text = "DeviceLink synthetic relay copy 20261008"
                withContext(Dispatchers.Main) { check(application.internetLink.sendText(text, true) == ClipSendResult.SENT) }
                await("remote text clipboard result", 45_000) { application.internetLink.state.value.transfers.any { it.text == text && it.clipboardStatus == ClipboardStatus.COPIED } }
                sendStatus(1, Bundle().apply { putString("step", "text clipboard acknowledged over relay with nearby off") })
                val image = application.imageClipboard.sample()
                withContext(Dispatchers.Main) { check(application.internetLink.sendFile(image.uri, true) == ClipSendResult.SENT) }
                await("remote image clipboard result", 45_000) { application.internetLink.state.value.transfers.any { it.kind == TransferKind.FILE && it.mode == TransferMode.CLIPBOARD && it.clipboardStatus == ClipboardStatus.COPIED } }
                sendStatus(2, Bundle().apply { putString("step", "image clipboard acknowledged over relay with nearby off") })
                withContext(Dispatchers.Main) { check(application.internetLink.sendFile(image.uri, false) == ClipSendResult.SENT) }
                sendStatus(3, Bundle().apply { putString("step", "ordinary image file offered; tap Receive on the other phone") })
                await("explicit file receipt", 120_000) { application.internetLink.state.value.transfers.any { it.kind == TransferKind.FILE && it.mode == TransferMode.STANDARD && it.status == TransferStatus.COMPLETE } }
                result.putString("result", "PASS: verified keys, nearby off, relay text/image clipboard acknowledgements, explicit file receipt")
                withContext(Dispatchers.Main) { application.internetLink.stop() }
            }
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            // Never emit payloads, URIs, keys or arbitrary exception messages.
            result.putString("result", "FAIL: ${failure.javaClass.simpleName}; inspect UI and server status")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private suspend fun await(step: String, timeout: Long, predicate: () -> Boolean) {
        sendStatus(0, Bundle().apply { putString("waiting", step) })
        withTimeout(timeout) { while (!predicate()) delay(250) }
    }
}
