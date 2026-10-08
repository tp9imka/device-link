@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.devicelink.feature.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.devicelink.designsystem.MessageBubble
import dev.devicelink.designsystem.LocalDeviceTokens
import dev.devicelink.model.LinkPhase
import dev.devicelink.model.LinkState
import dev.devicelink.model.WireCodec
import kotlinx.coroutines.launch

/**
 * Integration example: intercept explicit Compose copy actions within a chosen subtree.
 * The app callback owns local clipboard writes and optional paired-device delivery.
 * No clipboard observation, background reads, or provider around the app/paste editor.
 * Compose 1.12 SelectionContainer routes its toolbar Copy through LocalClipboard.
 */
@Composable
fun SampleClipboardProvider(onCopySample: (String) -> Unit, content: @Composable () -> Unit) {
    val platformClipboard = LocalClipboard.current
    val currentCopy by rememberUpdatedState(onCopySample)
    val sampleClipboard = remember(platformClipboard) {
        object : Clipboard by platformClipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                val data = clipEntry?.clipData
                val text = if (data?.itemCount == 1) data.getItemAt(0).text?.toString() else null
                if (text != null) currentCopy(text) else platformClipboard.setClipEntry(clipEntry)
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides sampleClipboard, content = content)
}

/** Messages are persisted locally by the application; there is no chat transport. */
data class SampleChatMessage(val id: String, val text: String)

private data class ClipboardSample(val title: String, val text: String, val outgoing: Boolean = false, val long: Boolean = false)

@Composable
internal fun ClipboardSamplesScreen(
    state: LinkState,
    localMessages: List<SampleChatMessage>,
    onCopySample: (String) -> Unit,
    onAddMessage: suspend (String) -> Boolean,
    onBack: () -> Unit,
) {
    val tokens = LocalDeviceTokens.current
    val connected = state.phase == LinkPhase.CONNECTED
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draftBytes = remember(draft) { draft.toByteArray(Charsets.UTF_8).size }
    val draftTooLarge = draftBytes > WireCodec.MAX_TEXT_BYTES
    val sampleBlock = stringResource(R.string.dl_sample_long_content)
    val longText = remember(sampleBlock) {
        // ASCII fixture is deliberately 8,192 UTF-8 bytes: the supported text boundary.
        sampleBlock.repeat((8192 / sampleBlock.length) + 1).take(8192)
    }
    val samples = listOf(
        ClipboardSample(stringResource(R.string.dl_sample_url), stringResource(R.string.dl_sample_url_content)),
        ClipboardSample(stringResource(R.string.dl_sample_address), stringResource(R.string.dl_sample_address_content), outgoing = true),
        ClipboardSample(stringResource(R.string.dl_sample_emoji), stringResource(R.string.dl_sample_emoji_content)),
        ClipboardSample(stringResource(R.string.dl_sample_rtl), stringResource(R.string.dl_sample_rtl_content), outgoing = true),
        ClipboardSample(stringResource(R.string.dl_sample_long), longText, long = true),
    )
    val listState = rememberLazyListState()
    LaunchedEffect(localMessages.lastOrNull()?.id) {
        if (localMessages.isNotEmpty()) listState.animateScrollToItem(samples.size + localMessages.size)
    }
    Scaffold(
        modifier = Modifier.widthIn(max = tokens.contentWidth).fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().padding(tokens.inset), horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.tiny)) {
                    Text(stringResource(R.string.dl_samples_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(if (connected) stringResource(R.string.dl_samples_linked, state.connectedPeerName.orEmpty()) else stringResource(R.string.dl_samples_local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.dl_done)) }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(tokens.inset), verticalArrangement = Arrangement.spacedBy(tokens.small)) {
                    // Intentionally outside SampleClipboardProvider: normal input copy/paste
                    // remains local. Send persists a local chat message; it never sends a Clip.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.dl_samples_paste_label)) },
                            placeholder = { Text(stringResource(R.string.dl_samples_paste_placeholder)) },
                            isError = draftTooLarge,
                            supportingText = if (draftTooLarge) ({ Text(stringResource(R.string.dl_samples_message_too_large, WireCodec.MAX_TEXT_BYTES, draftBytes)) }) else null,
                            minLines = 1, maxLines = 4,
                        )
                        Button(
                            onClick = {
                                val submitted = draft
                                saving = true
                                scope.launch {
                                    try {
                                        if (onAddMessage(submitted) && draft == submitted) draft = ""
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && draft.isNotBlank() && !draftTooLarge,
                        ) { Text(stringResource(if (saving) R.string.dl_samples_saving else R.string.dl_samples_send)) }
                    }
                    Text(stringResource(R.string.dl_samples_paste_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { insets ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(tokens.inset),
            verticalArrangement = Arrangement.spacedBy(tokens.inset),
        ) {
            item {
                Text(stringResource(if (connected) R.string.dl_samples_linked_hint else R.string.dl_samples_local_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(samples, key = { "seed-${it.title}" }) { sample ->
                ChatMessage(
                    text = sample.text,
                    outgoing = sample.outgoing,
                    sender = stringResource(R.string.dl_samples_sender_kind, stringResource(if (sample.outgoing) R.string.dl_samples_you else R.string.dl_samples_alex), sample.title),
                    long = sample.long,
                    onCopySample = onCopySample,
                )
            }
            items(localMessages, key = { "local-${it.id}" }) { message ->
                ChatMessage(message.text, outgoing = true, sender = stringResource(R.string.dl_samples_you), long = message.text.length > 600, onCopySample = onCopySample)
            }
        }
    }
}

@Composable
private fun ChatMessage(text: String, outgoing: Boolean, sender: String, long: Boolean, onCopySample: (String) -> Unit) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    MessageBubble(outgoing, sender) {
        SampleClipboardProvider(onCopySample) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = if (long && !expanded) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.small)) {
            TextButton(onClick = { onCopySample(text) }) { Text(stringResource(if (long) R.string.dl_samples_copy_full else R.string.dl_samples_copy_message)) }
            if (long) TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.dl_samples_collapse else R.string.dl_samples_expand)) }
        }
    }
}
