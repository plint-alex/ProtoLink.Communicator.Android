package ru.protolink.communicator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ru.protolink.communicator.util.NotesFormatBarStrings

/** Compact selection/IME format strip (UX-N-A1…A4). Hidden by caller when idle. */
@Composable
fun NotesFormatBar(
    lang: String,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strike: Boolean,
    checkbox: Boolean,
    block: String,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrike: () -> Unit,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onCheckbox: () -> Unit,
    onOutdent: () -> Unit,
    onIndent: () -> Unit,
    onLink: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F8FA))
            .border(1.dp, Color(0xFFE0E3E7))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FmtToggle("B", bold, onBold, NotesFormatBarStrings.bold(lang), boldWeight = true)
        FmtToggle("I", italic, onItalic, NotesFormatBarStrings.italic(lang), italicStyle = true)
        FmtToggle("U", underline, onUnderline, NotesFormatBarStrings.underline(lang), underline = true)
        FmtToggle("S", strike, onStrike, NotesFormatBarStrings.strikethrough(lang), strike = true)
        FmtSep()
        FmtToggle(
            when (block) {
                "h1" -> "H1"
                "h2" -> "H2"
                "h3" -> "H3"
                else -> "¶"
            },
            active = block == "h1" || block == "h2" || block == "h3",
            onClick = onHeading,
            description = NotesFormatBarStrings.textStyle(lang)
        )
        FmtSep()
        FmtToggle("•", false, onBullet, NotesFormatBarStrings.bulletList(lang))
        FmtToggle("1.", false, onNumbered, NotesFormatBarStrings.numberedList(lang))
        FmtToggle("☐", checkbox, onCheckbox, NotesFormatBarStrings.checkboxList(lang))
        FmtToggle("⇤", false, onOutdent, NotesFormatBarStrings.outdent(lang))
        FmtToggle("⇥", false, onIndent, NotesFormatBarStrings.indent(lang))
        FmtSep()
        FmtToggle("🔗", false, onLink, NotesFormatBarStrings.insertLink(lang))
    }
}

@Composable
private fun FmtSep() {
    Spacer(Modifier.width(4.dp))
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(Color(0xFFD0D5DB))
    )
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun FmtToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String,
    boldWeight: Boolean = false,
    italicStyle: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 44.dp)
            .padding(2.dp)
            .background(
                if (active) Color(0x1A2AABEE) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (boldWeight) FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (italicStyle) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    underline && strike -> TextDecoration.Underline + TextDecoration.LineThrough
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                }
            ),
            color = Color(0xFF1C1C1E)
        )
    }
}
