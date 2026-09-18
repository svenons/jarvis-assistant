package dk.foss.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tool the Hermes agent ran during a turn, for the live list on screen (never spoken). It is also saved to the conversation as a tool message. */
data class ToolStep(val id: String, val emoji: String, val label: String, val done: Boolean)

/** The one-line text of a tool step, as stored in the conversation. */
fun toolLine(emoji: String, tool: String, label: String) = "${emoji.ifBlank { "\u2699" }} ${label.ifBlank { tool }}"

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

/** A tool step in the chat transcript: a quiet line, blue while running, grey once done or when loaded from history. */
@Composable
fun ToolLine(text: String, running: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            fontFamily = DmSans,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (running) JarvisColors.ThinkBlue else JarvisColors.Muted,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (done) Text("✓", fontSize = 12.sp, color = JarvisColors.Muted, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The model's reasoning for a turn (fetched from Hermes once the turn is done): two lines collapsed, all of it when tapped. */
@Composable
fun ReasoningBlock(text: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reasoning", fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.ThinkBlue)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                tint = JarvisColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text.trim(),
            fontFamily = DmSans,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            color = JarvisColors.Muted,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
