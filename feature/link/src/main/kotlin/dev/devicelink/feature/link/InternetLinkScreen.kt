package dev.devicelink.feature.link

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.devicelink.designsystem.*
import dev.devicelink.model.*

data class InternetLinkCallbacks(
    val onConfigure: (String, String, Boolean) -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onSelectPeer: (String) -> Unit,
    val onForgetPeer: (String) -> Unit,
    val onSendClipboard: () -> Unit,
    val onPickFiles: () -> Unit,
    val onCopyText: (String) -> Unit,
    val onCopyImage: (String) -> Unit,
    val onReceiveFile: (String) -> Unit,
    val onRejectFile: (String) -> Unit,
    val onOpenFile: (Transfer) -> Unit,
    val onSaveFile: (Transfer) -> Unit,
    val onSample: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun InternetLinkScreen(state: InternetLinkState, callbacks: InternetLinkCallbacks) {
    val tokens = LocalDeviceTokens.current
    var url by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var token by rememberSaveable(state.enrollmentToken) { mutableStateOf(state.enrollmentToken) }
    var automatic by rememberSaveable(state.allowClipboard) { mutableStateOf(state.allowClipboard) }
    var revoke by remember { mutableStateOf<InternetPeer?>(null) }
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentPadding = PaddingValues(tokens.page), verticalArrangement = Arrangement.spacedBy(tokens.inset)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.internet_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = callbacks.onBack) { Text(stringResource(R.string.dl_done)) }
                }
            }
            item {
                LinkPanel {
                    Text(stringResource(when (state.status) {
                        InternetStatus.OFF -> R.string.internet_off
                        InternetStatus.CONNECTING -> R.string.internet_connecting
                        InternetStatus.READY -> R.string.internet_ready
                        InternetStatus.RETRYING -> R.string.internet_retrying
                        InternetStatus.CONFIGURATION_ERROR -> R.string.internet_error
                    }), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.internet_explanation), style = MaterialTheme.typography.bodyMedium)
                    if (state.enabled) {
                        Text(if (state.finishing) stringResource(R.string.internet_finishing)
                            else stringResource(R.string.internet_remaining, (state.remainingSeconds + 59) / 60))
                        Button(onClick = callbacks.onStop) { Text(stringResource(R.string.internet_stop)) }
                    } else Button(onClick = callbacks.onStart, enabled = state.serverUrl.isNotBlank()) { Text(stringResource(R.string.internet_start)) }
                }
            }
            item {
                LinkPanel {
                    Text(stringResource(R.string.internet_server), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.internet_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.internet_token)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.internet_auto_copy), modifier = Modifier.weight(1f))
                        Switch(automatic, { automatic = it })
                    }
                    Text(stringResource(R.string.internet_auto_copy_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { callbacks.onConfigure(url, token, automatic) }) { Text(stringResource(R.string.internet_apply)) }
                }
            }
            item { Text(stringResource(R.string.internet_devices), style = MaterialTheme.typography.titleLarge) }
            if (state.peers.isEmpty()) item { Text(stringResource(R.string.internet_pair_hint)) }
            items(state.peers, key = { "peer-${it.id}" }) { peer ->
                LinkPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = peer.id == state.selectedPeerId, onClick = { callbacks.onSelectPeer(peer.id) })
                        Text(peer.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { revoke = peer }) { Text(stringResource(R.string.internet_revoke)) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(tokens.small)) {
                    Button(callbacks.onSendClipboard, enabled = !state.finishing && state.status == InternetStatus.READY && state.selectedPeer != null) { Text(stringResource(R.string.dl_clipboard)) }
                    OutlinedButton(callbacks.onPickFiles, enabled = !state.finishing && state.status == InternetStatus.READY && state.selectedPeer != null) { Text(stringResource(R.string.dl_files)) }
                    Text(stringResource(R.string.internet_file_limit), style = MaterialTheme.typography.bodyMedium)
                    TextButton(callbacks.onSample) { Text(stringResource(R.string.dl_samples_entry)) }
                }
            }
            items(state.transfers, key = { "transfer-${it.id}" }) { item ->
                LinkPanel {
                    Text(item.name.ifBlank { stringResource(R.string.internet_text) }, style = MaterialTheme.typography.titleMedium)
                    item.text?.let { Text(it, maxLines = 4, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(when {
                        item.status == TransferStatus.OFFERED -> R.string.internet_file_offer
                        item.status == TransferStatus.FAILED -> R.string.internet_delivery_failed
                        item.status == TransferStatus.REJECTED -> R.string.internet_declined
                        item.clipboardStatus == ClipboardStatus.COPIED -> R.string.internet_copied
                        item.clipboardStatus == ClipboardStatus.NOT_COPIED -> R.string.internet_manual
                        item.status == TransferStatus.COMPLETE -> R.string.internet_received
                        else -> R.string.internet_waiting
                    }))
                    if (item.direction == TransferDirection.INCOMING) {
                        item.text?.let { text -> TextButton({ callbacks.onCopyText(text) }) { Text(stringResource(R.string.internet_copy)) } }
                        if (item.status == TransferStatus.OFFERED) {
                            Button({ callbacks.onReceiveFile(item.id) }, enabled = state.enabled && !state.finishing) { Text(stringResource(R.string.internet_receive)) }
                            TextButton({ callbacks.onRejectFile(item.id) }, enabled = state.enabled && !state.finishing) { Text(stringResource(R.string.internet_decline)) }
                        }
                        if (item.localUri != null) {
                            if (item.mimeType.startsWith("image/")) TextButton({ callbacks.onCopyImage(requireNotNull(item.localUri)) }) { Text(stringResource(R.string.internet_copy_image)) }
                            TextButton({ callbacks.onOpenFile(item) }) { Text(stringResource(R.string.internet_open)) }
                            TextButton({ callbacks.onSaveFile(item) }) { Text(stringResource(R.string.internet_save)) }
                        }
                    }
                }
            }
        }
    }
    revoke?.let { peer -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text(stringResource(R.string.internet_revoke)) },
        text = { Text(stringResource(R.string.internet_revoke_body, peer.name)) },
        confirmButton = { TextButton({ callbacks.onForgetPeer(peer.id); revoke = null }) { Text(stringResource(R.string.internet_revoke)) } },
        dismissButton = { TextButton({ revoke = null }) { Text(stringResource(R.string.dl_done)) } }) }
}
