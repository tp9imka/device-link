@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.devicelink.feature.link

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
data class SampleChatImage(val uri: String, val mime: String)
data class SampleChatMessage(val id: String, val text: String, val imageUri: String? = null, val imageMime: String? = null)

private data class ClipboardSample(val title: String, val text: String, val outgoing: Boolean = false, val long: Boolean = false)

@Composable
internal fun ClipboardSamplesScreen(
    state: LinkState,
    localMessages: List<SampleChatMessage>,
    onCopySample: (String) -> Unit,
    onAddMessage: suspend (String) -> Boolean,
    onBack: () -> Unit,
    sampleImage: SampleChatImage? = null,
    relayPeerName: String? = null,
    onCopyImage: (String) -> Unit,
    onPasteImage: suspend (String) -> SampleChatImage?,
    onAddImage: suspend (String, SampleChatImage) -> Boolean,
) {
    val tokens = LocalDeviceTokens.current
    val connected = state.phase == LinkPhase.CONNECTED || relayPeerName != null
    val editor = rememberTextFieldState()
    val draft = editor.text.toString()
    var attachment by remember { mutableStateOf<SampleChatImage?>(null) }
    var importing by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf(false) }
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
    LaunchedEffect(localMessages.lastOrNull()?.id, sampleImage?.uri) {
        if (localMessages.isNotEmpty()) listState.animateScrollToItem(samples.size + localMessages.size + if (sampleImage == null) 0 else 1)
    }
    Scaffold(
        modifier = Modifier.widthIn(max = tokens.contentWidth).fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().padding(tokens.inset), horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.tiny)) {
                    Text(stringResource(R.string.dl_samples_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(if (connected) stringResource(R.string.dl_samples_linked, (state.connectedPeerName ?: relayPeerName).orEmpty()) else stringResource(R.string.dl_samples_local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.dl_done)) }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(tokens.inset), verticalArrangement = Arrangement.spacedBy(tokens.small)) {
                    attachment?.let { image ->
                        ImagePreview(image, compact = true)
                        TextButton(onClick = { attachment = null }) { Text(stringResource(R.string.dl_image_remove)) }
                    }
                    if (importing) Text(stringResource(R.string.dl_image_importing), style = MaterialTheme.typography.bodyMedium)
                    if (imageError) Text(stringResource(R.string.dl_image_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    // Intentionally outside SampleClipboardProvider: normal input copy/paste
                    // remains local. Send persists a local chat message; it never sends a Clip.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.small), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            state = editor, modifier = Modifier.weight(1f).contentReceiver(ReceiveContentListener { content ->
                                var selected = false
                                content.consume { item ->
                                    val uri = item.uri
                                    if (uri == null) false else {
                                        // Consume URI payloads even when invalid: never paste image URI text.
                                        if (!selected && !importing) {
                                            selected = true; importing = true; imageError = false
                                            scope.launch {
                                                try {
                                                    val imported = onPasteImage(uri.toString())
                                                    if (imported != null) attachment = imported else imageError = true
                                                } finally { importing = false }
                                            }
                                        }
                                        true
                                    }
                                }
                            }),
                            label = { Text(stringResource(R.string.dl_samples_paste_label)) },
                            placeholder = { Text(stringResource(R.string.dl_samples_paste_placeholder)) },
                            isError = draftTooLarge,
                            supportingText = if (draftTooLarge) ({ Text(stringResource(R.string.dl_samples_message_too_large, WireCodec.MAX_TEXT_BYTES, draftBytes)) }) else null,
                            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
                        )
                        Button(
                            onClick = {
                                val submitted = draft
                                val submittedImage = attachment
                                saving = true
                                scope.launch {
                                    try {
                                        val saved = if (submittedImage == null) onAddMessage(submitted) else onAddImage(submitted, submittedImage)
                                        if (saved) {
                                            if (editor.text.toString() == submitted) editor.edit { replace(0, length, "") }
                                            if (attachment == submittedImage) attachment = null
                                        }
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && !importing && (draft.isNotBlank() || attachment != null) && !draftTooLarge,
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
            if (sampleImage != null) item(key = "seed-image") {
                MessageBubble(false, stringResource(R.string.dl_image_sample_sender)) {
                    ImagePreview(sampleImage)
                    TextButton(onClick = { onCopyImage(sampleImage.uri) }) { Text(stringResource(R.string.dl_copy_image)) }
                }
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
                if (message.imageUri != null && message.imageMime != null) {
                    MessageBubble(true, stringResource(R.string.dl_samples_you)) {
                        ImagePreview(SampleChatImage(message.imageUri, message.imageMime))
                        TextButton(onClick = { onCopyImage(message.imageUri) }) { Text(stringResource(R.string.dl_copy_image)) }
                        if (message.text.isNotBlank()) SampleClipboardProvider(onCopySample) { SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) } }
                    }
                } else ChatMessage(message.text, outgoing = true, sender = stringResource(R.string.dl_samples_you), long = message.text.length > 600, onCopySample = onCopySample)
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

@Composable
private fun ImagePreview(image: SampleChatImage, compact: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(image.uri)).use { BitmapFactory.decodeStream(it, null, options) }
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 40_000_000)
                options.inJustDecodeBounds = false
                options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / 512).coerceAtLeast(1))
                context.contentResolver.openInputStream(Uri.parse(image.uri)).use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    val tokens = LocalDeviceTokens.current
    bitmap?.let { Image(it, contentDescription = stringResource(R.string.dl_image_description), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(if (compact) tokens.imageAttachmentHeight else tokens.imageMessageHeight)) }
        ?: Text(stringResource(R.string.dl_image_loading), style = MaterialTheme.typography.bodyMedium)
}
