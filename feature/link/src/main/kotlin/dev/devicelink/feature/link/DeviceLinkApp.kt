@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.devicelink.feature.link

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.devicelink.designsystem.Accent
import dev.devicelink.designsystem.Appearance
import dev.devicelink.designsystem.AppearanceMode
import dev.devicelink.designsystem.AppearanceStore
import dev.devicelink.designsystem.Corners
import dev.devicelink.designsystem.DeviceIdentity
import dev.devicelink.designsystem.DeviceMark
import dev.devicelink.designsystem.DeviceTheme
import dev.devicelink.designsystem.LinkPanel
import dev.devicelink.designsystem.LocalDeviceTokens
import dev.devicelink.designsystem.QuietMessage
import dev.devicelink.designsystem.SectionHeading
import dev.devicelink.model.LinkController
import dev.devicelink.model.LinkPhase
import dev.devicelink.model.LinkState
import dev.devicelink.model.Peer
import dev.devicelink.model.Transfer
import dev.devicelink.model.TransferDirection
import dev.devicelink.model.TransferKind
import dev.devicelink.model.TransferStatus

data class LinkCallbacks(
    val onStartSession: (Int) -> Unit,
    val onSendClipboard: () -> Unit,
    val onPickFiles: () -> Unit,
    val onScanCode: () -> Unit,
    val onCopyText: (String) -> Unit,
    val onOpenFile: (Transfer) -> Unit,
    val onSaveFile: (Transfer) -> Unit,
    val onShareFile: (Transfer) -> Unit,
)

@Composable
fun DeviceLinkApp(state: LinkState, controller: LinkController, appearanceStore: AppearanceStore, callbacks: LinkCallbacks) {
    val appearance by appearanceStore.appearance.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    var pairMenu by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    BackHandler(settings || pairMenu || showQr) {
        when { showQr -> showQr = false; pairMenu -> pairMenu = false; else -> settings = false }
    }
    val stop = { if (state.hasActiveTransfers) confirmStop = true else controller.stopSession() }
    DeviceTheme(appearance) {
        val tokens = LocalDeviceTokens.current
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                if (settings) {
                    SettingsScreen(state, appearance, appearanceStore::update, controller, onBack = { settings = false })
                } else {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = tokens.contentWidth).fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(tokens.page),
                        verticalArrangement = Arrangement.spacedBy(tokens.page),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.tiny)) {
                                    Text(stringResource(R.string.dl_brand), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                                    Text(state.localName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { settings = true }) { Text(stringResource(R.string.dl_settings)) }
                            }
                        }
                        item { SessionPanel(state, appearance.sessionMinutes, callbacks.onStartSession, stop) }
                        if (state.pendingItems > 0) item {
                            LinkPanel {
                                Text(pluralStringResource(R.plurals.dl_pending, state.pendingItems, state.pendingItems), style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = controller::clearPending) { Text(stringResource(R.string.dl_clear_pending)) }
                            }
                        }
                        if (state.phase == LinkPhase.CONNECTED) item {
                            Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                                SectionHeading(stringResource(R.string.dl_send))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalArrangement = Arrangement.spacedBy(tokens.small)) {
                                    Button(onClick = callbacks.onSendClipboard, enabled = state.phase == LinkPhase.CONNECTED) { Text(stringResource(R.string.dl_clipboard)) }
                                    OutlinedButton(onClick = callbacks.onPickFiles, enabled = state.phase == LinkPhase.CONNECTED) { Text(stringResource(R.string.dl_files)) }
                                }
                                Text(stringResource(R.string.dl_send_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.phase != LinkPhase.CONNECTED) item {
                            Column(verticalArrangement = Arrangement.spacedBy(tokens.inset)) {
                                SectionHeading(stringResource(R.string.dl_nearby), stringResource(R.string.dl_nearby_subtitle))
                                OutlinedButton(onClick = { pairMenu = true }) { Text(stringResource(R.string.dl_pair_qr)) }
                                state.nearbyPeers.forEach { peer ->
                                    LinkPanel {
                                        DeviceIdentity(peer.name, stringResource(R.string.dl_available))
                                        Button(onClick = { controller.connect(peer.id) }, enabled = state.phase == LinkPhase.SEARCHING) { Text(stringResource(R.string.dl_connect)) }
                                    }
                                }
                                if (state.nearbyPeers.isEmpty() && state.trustedPeers.isNotEmpty()) {
                                    LinkPanel {
                                        state.trustedPeers.forEach { DeviceIdentity(it.name, stringResource(R.string.dl_remembered)) }
                                        Text(stringResource(R.string.dl_search_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item { SectionHeading(stringResource(R.string.dl_tray), stringResource(R.string.dl_tray_subtitle)) }
                        if (state.transfers.isEmpty()) item {
                            LinkPanel { QuietMessage(stringResource(R.string.dl_empty_title), stringResource(R.string.dl_empty_body)) }
                        }
                        items(state.transfers.asReversed(), key = { it.id }) { transfer -> TransferCard(transfer, controller, callbacks) }
                        item { Text(stringResource(R.string.dl_battery), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (pairMenu) AlertDialog(
            onDismissRequest = { pairMenu = false },
            title = { Text(stringResource(R.string.dl_pair_qr)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    Text(stringResource(if (state.enabled) R.string.dl_qr_body else R.string.dl_pair_first))
                    if (state.enabled) {
                        Button(onClick = { pairMenu = false; callbacks.onScanCode() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dl_scan)) }
                        OutlinedButton(onClick = { pairMenu = false; showQr = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dl_show_qr)) }
                    } else {
                        Button(onClick = { callbacks.onStartSession(appearance.sessionMinutes) }) { Text(stringResource(R.string.dl_start_short)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pairMenu = false }) { Text(stringResource(R.string.dl_close)) } },
        )
        if (showQr) QrDialog(controller.pairingCode, onDismiss = { showQr = false })
        state.verification?.let { verification ->
            AlertDialog(
                onDismissRequest = { controller.confirmPairing(false) },
                title = { Text(stringResource(R.string.dl_verify_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(tokens.page)) {
                        Text(stringResource(R.string.dl_verify_body, verification.peerName))
                        Text(verification.code, style = MaterialTheme.typography.headlineLarge)
                    }
                },
                confirmButton = { Button(onClick = { controller.confirmPairing(true) }) { Text(stringResource(R.string.dl_verify_yes)) } },
                dismissButton = { TextButton(onClick = { controller.confirmPairing(false) }) { Text(stringResource(R.string.dl_verify_no)) } },
            )
        }
        if (confirmStop) AlertDialog(
            onDismissRequest = { confirmStop = false }, title = { Text(stringResource(R.string.dl_stop_title)) },
            text = { Text(stringResource(R.string.dl_stop_body)) },
            confirmButton = { Button(onClick = { confirmStop = false; controller.stopSession() }) { Text(stringResource(R.string.dl_stop)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.dl_cancel)) } },
        )
        state.error?.let { error ->
            AlertDialog(onDismissRequest = controller::dismissError, title = { Text(stringResource(R.string.dl_error)) },
                text = { Text(error) }, confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(R.string.dl_done)) } })
        }
    }
}

@Composable
private fun SessionPanel(state: LinkState, duration: Int, start: (Int) -> Unit, stop: () -> Unit) {
    val tokens = LocalDeviceTokens.current
    val sessionLabel = stringResource(R.string.dl_session)
    val waitingForApproval = state.phase == LinkPhase.VERIFYING && state.verification == null
    LinkPanel(emphasized = state.enabled) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.inset)) {
            DeviceMark(linked = state.phase == LinkPhase.CONNECTED, large = !state.enabled)
            Column(Modifier.weight(1f)) {
                Text(sessionLabel, style = MaterialTheme.typography.titleMedium)
                if (state.enabled) {
                    val time = String.format(LocalConfiguration.current.locales[0], "%d:%02d", state.remainingSeconds.coerceAtLeast(0) / 60, state.remainingSeconds.coerceAtLeast(0) % 60)
                    Text(if (state.remainingSeconds <= 0 && state.hasActiveTransfers) stringResource(R.string.dl_finishing) else stringResource(R.string.dl_time_left, time), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Switch(checked = state.enabled, onCheckedChange = { if (it) start(duration) else stop() }, modifier = Modifier.semantics { contentDescription = sessionLabel })
        }
        Text(when (state.phase) {
            LinkPhase.OFF -> stringResource(R.string.dl_off_title)
            LinkPhase.SEARCHING -> stringResource(R.string.dl_searching)
            LinkPhase.CONNECTING -> stringResource(R.string.dl_connecting)
            LinkPhase.VERIFYING -> stringResource(if (waitingForApproval) R.string.dl_waiting_approval else R.string.dl_verifying)
            LinkPhase.CONNECTED -> stringResource(R.string.dl_connected, state.connectedPeerName.orEmpty())
        }, style = if (state.enabled) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
        Text(stringResource(when (state.phase) {
            LinkPhase.OFF -> R.string.dl_off_body
            LinkPhase.CONNECTED -> R.string.dl_connected_body
            LinkPhase.CONNECTING -> R.string.dl_connecting_body
            LinkPhase.VERIFYING -> if (waitingForApproval) R.string.dl_waiting_approval_body else R.string.dl_verifying_body
            LinkPhase.SEARCHING -> R.string.dl_search_body
        }), style = if (state.enabled) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
        if (!state.enabled) Button(onClick = { start(duration) }) { Text(stringResource(R.string.dl_start, duration)) }
        if (state.phase == LinkPhase.SEARCHING || state.phase == LinkPhase.CONNECTING || waitingForApproval) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun TransferCard(transfer: Transfer, controller: LinkController, callbacks: LinkCallbacks) {
    val tokens = LocalDeviceTokens.current
    val incoming = transfer.direction == TransferDirection.INCOMING
    val text = transfer.kind == TransferKind.TEXT
    val progress = if (transfer.totalBytes > 0) (transfer.transferredBytes.toDouble() / transfer.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    LinkPanel {
        Column(verticalArrangement = Arrangement.spacedBy(tokens.tiny)) {
            Text(stringResource(if (incoming) R.string.dl_incoming else R.string.dl_outgoing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (text) stringResource(R.string.dl_text) else transfer.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!text && transfer.totalBytes > 0) Text(Formatter.formatShortFileSize(LocalContext.current, transfer.totalBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (text && transfer.status == TransferStatus.COMPLETE) transfer.text?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        Text(when (transfer.status) {
            TransferStatus.OFFERED -> stringResource(if (incoming) R.string.dl_offer_incoming else R.string.dl_offer_outgoing)
            TransferStatus.TRANSFERRING -> stringResource(R.string.dl_transferring, (progress * 100).toInt())
            TransferStatus.COMPLETE -> stringResource(if (incoming) R.string.dl_complete else R.string.dl_sent)
            TransferStatus.REJECTED -> stringResource(R.string.dl_rejected)
            TransferStatus.CANCELLED -> stringResource(R.string.dl_cancelled)
            TransferStatus.FAILED -> stringResource(R.string.dl_failed)
        }, style = MaterialTheme.typography.bodyMedium, color = if (transfer.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (transfer.status == TransferStatus.TRANSFERRING) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalArrangement = Arrangement.spacedBy(tokens.tiny)) {
            when (transfer.status) {
                TransferStatus.OFFERED -> if (incoming) {
                    Button(onClick = { controller.acceptTransfer(transfer.id) }) { Text(stringResource(R.string.dl_accept)) }
                    TextButton(onClick = { controller.rejectTransfer(transfer.id) }) { Text(stringResource(R.string.dl_reject)) }
                } else TextButton(onClick = { controller.cancelTransfer(transfer.id) }) { Text(stringResource(R.string.dl_cancel)) }
                TransferStatus.TRANSFERRING -> TextButton(onClick = { controller.cancelTransfer(transfer.id) }) { Text(stringResource(R.string.dl_cancel)) }
                TransferStatus.COMPLETE -> {
                    if (incoming && text) transfer.text?.let { content -> Button(onClick = { callbacks.onCopyText(content) }) { Text(stringResource(R.string.dl_copy)) } }
                    if (incoming && !text && transfer.localUri != null) {
                        Button(onClick = { callbacks.onOpenFile(transfer) }) { Text(stringResource(R.string.dl_open)) }
                        OutlinedButton(onClick = { callbacks.onSaveFile(transfer) }) { Text(stringResource(R.string.dl_save)) }
                        TextButton(onClick = { callbacks.onShareFile(transfer) }) { Text(stringResource(R.string.dl_share)) }
                    }
                    TextButton(onClick = { controller.clearTransfer(transfer.id) }) { Text(stringResource(R.string.dl_remove)) }
                }
                else -> TextButton(onClick = { controller.clearTransfer(transfer.id) }) { Text(stringResource(R.string.dl_remove)) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: LinkState, appearance: Appearance, update: (Appearance) -> Unit, controller: LinkController, onBack: () -> Unit) {
    val tokens = LocalDeviceTokens.current
    var rename by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(state.localName) { mutableStateOf(state.localName) }
    var forget by remember { mutableStateOf<Peer?>(null) }
    LazyColumn(modifier = Modifier.widthIn(max = tokens.contentWidth).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(tokens.page), verticalArrangement = Arrangement.spacedBy(tokens.page)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dl_settings), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onBack) { Text(stringResource(R.string.dl_done)) }
            }
        }
        item {
            LinkPanel {
                SectionHeading(stringResource(R.string.dl_this_phone))
                DeviceIdentity(state.localName, stringResource(R.string.dl_name_hint))
                OutlinedButton(onClick = { name = state.localName; rename = true }) { Text(stringResource(R.string.dl_rename)) }
            }
        }
        item {
            LinkPanel {
                SectionHeading(stringResource(R.string.dl_default_session), stringResource(R.string.dl_session_hint))
                Choices(listOf(5, 15, 30), appearance.sessionMinutes, label = { stringResource(R.string.dl_minutes, it) }) { update(appearance.copy(sessionMinutes = it)) }
            }
        }
        item {
            LinkPanel {
                SectionHeading(stringResource(R.string.dl_appearance), stringResource(R.string.dl_appearance_hint))
                Text(stringResource(R.string.dl_theme), style = MaterialTheme.typography.titleMedium)
                Choices(AppearanceMode.entries, appearance.mode, label = { stringResource(when (it) { AppearanceMode.SYSTEM -> R.string.dl_system; AppearanceMode.LIGHT -> R.string.dl_light; AppearanceMode.DARK -> R.string.dl_dark }) }) { update(appearance.copy(mode = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dl_dynamic), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.dl_dynamic_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val label = stringResource(R.string.dl_dynamic)
                    Switch(checked = appearance.dynamicColor, onCheckedChange = { update(appearance.copy(dynamicColor = it)) }, enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, modifier = Modifier.semantics { contentDescription = label })
                }
                if (!appearance.dynamicColor) {
                    Text(stringResource(R.string.dl_accent), style = MaterialTheme.typography.titleMedium)
                    Choices(Accent.entries, appearance.accent, label = { stringResource(when (it) { Accent.OCEAN -> R.string.dl_ocean; Accent.IRIS -> R.string.dl_iris; Accent.FOREST -> R.string.dl_forest }) }) { update(appearance.copy(accent = it)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.dl_shape), style = MaterialTheme.typography.titleMedium)
                Choices(Corners.entries, appearance.corners, label = { stringResource(when (it) { Corners.SOFT -> R.string.dl_soft; Corners.ROUND -> R.string.dl_round; Corners.SQUARE -> R.string.dl_square }) }) { update(appearance.copy(corners = it)) }
            }
        }
        item {
            LinkPanel {
                SectionHeading(stringResource(R.string.dl_paired))
                if (state.trustedPeers.isEmpty()) Text(stringResource(R.string.dl_no_paired), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.trustedPeers.forEach { peer ->
                    DeviceIdentity(peer.name, stringResource(R.string.dl_remembered))
                    TextButton(onClick = { forget = peer }) { Text(stringResource(R.string.dl_forget)) }
                }
            }
        }
        item { QuietMessage(stringResource(R.string.dl_privacy_title), stringResource(R.string.dl_privacy_body)) }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.dl_name)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(stringResource(R.string.dl_name)) }, singleLine = true) },
        confirmButton = { Button(onClick = { controller.setDeviceName(name.trim()); rename = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.dl_save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.dl_cancel)) } },
    )
    forget?.let { peer ->
        AlertDialog(onDismissRequest = { forget = null }, title = { Text(stringResource(R.string.dl_forget_title, peer.name)) }, text = { Text(stringResource(R.string.dl_forget_body)) },
            confirmButton = { Button(onClick = { controller.forgetPeer(peer.id); forget = null }) { Text(stringResource(R.string.dl_forget)) } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.dl_cancel)) } })
    }
}

@Composable
private fun <T> Choices(values: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.small), verticalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.tiny)) {
        values.forEach { value -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
    }
}

@Composable
private fun QrDialog(code: String, onDismiss: () -> Unit) {
    val tokens = LocalDeviceTokens.current
    val bitmap = remember(code, tokens.qrPixels) {
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, tokens.qrPixels, tokens.qrPixels, mapOf(EncodeHintType.MARGIN to 2))
        createBitmap(matrix.width, matrix.height).also { bitmap ->
            val pixels = IntArray(matrix.width * matrix.height) { offset -> if (matrix[offset % matrix.width, offset / matrix.width]) tokens.qrInk else tokens.qrPaper }
            bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }.asImageBitmap()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.dl_qr_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tokens.inset)) {
            Image(bitmap, contentDescription = stringResource(R.string.dl_qr_description), modifier = Modifier.size(tokens.qrSize).background(tokens.qrBackground))
            Text(stringResource(R.string.dl_qr_body), style = MaterialTheme.typography.bodyMedium)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dl_close)) } })
}
