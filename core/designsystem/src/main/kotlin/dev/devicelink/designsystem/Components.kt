package dev.devicelink.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun LinkPanel(modifier: Modifier = Modifier, emphasized: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalDeviceTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(tokens.page), verticalArrangement = Arrangement.spacedBy(tokens.inset), content = content)
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.small)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.tiny)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
fun DeviceMark(modifier: Modifier = Modifier, linked: Boolean = false, large: Boolean = false) {
    val tokens = LocalDeviceTokens.current
    val ink = MaterialTheme.colorScheme.primary
    Box(modifier.size(if (large) tokens.heroMark else tokens.avatar).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (large) tokens.avatar else tokens.section)) {
            val stroke = size.width * .055f
            drawRoundRect(ink, Offset(size.width * .2f, size.height * .1f), Size(size.width * .46f, size.height * .76f), CornerRadius(size.width * .08f), style = Stroke(stroke))
            drawLine(ink, Offset(size.width * .35f, size.height * .74f), Offset(size.width * .51f, size.height * .74f), stroke)
            if (linked) {
                drawCircle(ink, size.width * .17f, Offset(size.width * .72f, size.height * .66f))
            }
        }
    }
}

@Composable
fun DeviceIdentity(name: String, subtitle: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.gap)) {
        DeviceMark()
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
fun QuietMessage(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalDeviceTokens.current.small)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MessageBubble(outgoing: Boolean, sender: String, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalDeviceTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(tokens.messageWidthFraction), shape = MaterialTheme.shapes.medium,
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(tokens.inset), verticalArrangement = Arrangement.spacedBy(tokens.small)) {
                Text(sender, style = MaterialTheme.typography.labelSmall)
                content()
            }
        }
    }
}
