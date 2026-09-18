package dk.foss.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tool the Hermes agent ran during a turn — shown on screen only, never spoken or saved. */
data class ToolStep(val id: String, val emoji: String, val label: String, val done: Boolean)

/** Apply a `hermes.tool.progress` event: a start adds a running row, a finish ticks it off. */
fun MutableList<ToolStep>.applyToolEvent(id: String, tool: String, emoji: String, label: String, running: Boolean) {
    val i = indexOfFirst { it.id == id }
    when {
        running && i < 0 -> add(ToolStep(id, emoji.ifBlank { "⚙" }, label.ifBlank { tool }, done = false))
        !running && i >= 0 -> this[i] = this[i].copy(done = true)
    }
}

/** What the agent is doing right now: the latest [maxRows] tool steps, running ones highlighted. */
@Composable
fun ToolActivity(steps: List<ToolStep>, modifier: Modifier = Modifier, maxRows: Int = 4) {
    if (steps.isEmpty()) return
    val shown = steps.takeLast(maxRows)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (steps.size > shown.size) {
            Text(
                "+${steps.size - shown.size} earlier",
                fontFamily = DmSans,
                fontSize = 12.sp,
                color = JarvisColors.Muted,
            )
        }
        shown.forEach { step ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(step.emoji, fontSize = 13.sp)
                Text(
                    step.label,
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (step.done) JarvisColors.Muted else JarvisColors.ThinkBlue,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (step.done) {
                    Text("✓", fontSize = 12.sp, color = JarvisColors.Muted, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
