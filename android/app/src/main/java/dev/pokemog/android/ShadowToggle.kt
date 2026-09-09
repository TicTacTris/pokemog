package dev.pokemog.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ShadowToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (checked) Color(ShadowStyle.on.fill) else scheme.surfaceVariant
    val text = if (checked) Color(ShadowStyle.on.text) else scheme.onSurface
    val outline = if (checked) Color(ShadowStyle.on.outline) else scheme.outline
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.6f)
            .testTag("shadow-toggle")
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {
                contentDescription = "Shadow"
                stateDescription = if (checked) "On" else "Off"
            },
        shape = PixelShape, color = fill, contentColor = text, border = BorderStroke(2.dp, outline),
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(21.dp)) {
                val unit = size.width / 7
                ShadowStyle.ghost.forEachIndexed { y, row -> row.forEachIndexed { x, pixel ->
                    if (pixel == '1') drawRect(text, Offset(x * unit, y * unit), Size(unit, unit))
                } }
            }
            Text("Shadow ${if (checked) "ON" else "OFF"}", style = MaterialTheme.typography.labelLarge)
        }
    }
}
