package ru.protolink.communicator.ui

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/** Builds the shared notes editor HTML document (same shell as Windows). */
object NotesEditorHtml {
    private const val EXTRA_STYLES = """
ul.checkbox-list{list-style:none;padding-left:0;}
ul.checkbox-list li{padding:8px 0;display:flex;align-items:flex-start;}
ul.checkbox-list li input[type="checkbox"]{margin-right:10px;margin-top:4px;cursor:pointer;flex-shrink:0;width:20px;height:20px;}
ul.checkbox-list li label{cursor:text;flex:1;margin:0;font-size:16px;}
ul.checkbox-list li:has(input:checked){text-decoration:line-through;opacity:0.6;}
table{border-collapse:collapse;margin:0.5em 0;width:100%;}
td,th{border:1px solid #dfe1e6;padding:8px;}
pre,code{font-family:monospace;background:#f4f5f7;border-radius:3px;}
pre{padding:8px 12px;overflow:auto;}
code{padding:1px 4px;}
"""

    fun wrap(context: Context, noteHtml: String): String {
        val healed = healJsEscapedHtml(noteHtml.trim())
        val inner = when {
            healed.isBlank() -> "<p><br></p>"
            else -> extractBodyInner(healed)
        }
        val base64 = Base64.encodeToString(inner.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val script = context.assets.open("notes-editor.js").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
<style>
html{color-scheme:light;background:#ffffff;}
html,body{height:100%;margin:0;background:#ffffff;color:#111111;}
body{font-family:sans-serif;line-height:1.6;padding:12px 14px 24px;min-height:100%;box-sizing:border-box;}
#editor{outline:none;min-height:50vh;font-size:16px;padding:4px 2px 40vh;box-sizing:border-box;background:#ffffff;color:#111111;-webkit-user-select:text;user-select:text;}
#editor:focus{outline:none;}
h1,h2,h3{margin-top:1em;margin-bottom:0.5em;}
p{margin:0.5em 0;}
ul,ol{padding-left:1.6em;}
$EXTRA_STYLES
a{color:#0366d6;text-decoration:underline;}
</style>
</head>
<body>
<div id="editor" contenteditable="true" data-content-base64="$base64"></div>
<script>
$script
</script>
</body>
</html>
""".trimIndent()
    }

    private fun extractBodyInner(html: String): String {
        val bodyMatch = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(html)
        if (bodyMatch != null) {
            var inner = bodyMatch.groupValues[1].trim()
            // If already a full editor document, take #editor contents when present.
            val editorMatch = Regex(
                "<div[^>]*id=[\"']editor[\"'][^>]*>([\\s\\S]*)</div>",
                RegexOption.IGNORE_CASE
            ).find(inner)
            if (editorMatch != null) return editorMatch.groupValues[1].trim().ifBlank { "<p><br></p>" }
            // Strip contenteditable wrappers from older Android saves.
            inner = inner.replace(Regex("\\scontenteditable\\s*=\\s*[\"'][^\"']*[\"']", RegexOption.IGNORE_CASE), "")
            return inner.ifBlank { "<p><br></p>" }
        }
        if (html.contains("contenteditable", ignoreCase = true) && html.contains("<html", ignoreCase = true)) {
            return "<p><br></p>"
        }
        return html
    }
}

const val NOTES_GET_HTML_JS =
    "(function(){try{if(typeof window.getHtml==='function')return window.getHtml();var e=document.getElementById('editor');return e?e.innerHTML:'';}catch(e){return '';}})();"

fun notesEditorApplyJs(command: String): String {
    val c = command.replace("\\", "\\\\").replace("'", "\\'")
    return "(function(){if(window.notesEditor)window.notesEditor.apply('$c');})();"
}

fun notesEditorInsertLinkJs(url: String): String {
    val safe = url.replace("\\", "\\\\").replace("'", "\\'")
    return "(function(){if(window.notesEditor)window.notesEditor.insertLink('$safe');})();"
}

/** Checkbox list insert — mirrors Windows NotesWebFormatting.ApplyCheckboxListAsync */
const val NOTES_CHECKBOX_LIST_JS = """
(function() {
    const editor = document.getElementById('editor');
    if (!editor) return;
    editor.focus();
    const selection = window.getSelection();
    if (selection.rangeCount > 0) {
        const range = selection.getRangeAt(0);
        let listItem = range.commonAncestorContainer;
        while (listItem && listItem.nodeType !== Node.ELEMENT_NODE) listItem = listItem.parentNode;
        while (listItem && listItem.tagName !== 'LI' && listItem.tagName !== 'UL' && listItem.tagName !== 'OL') {
            listItem = listItem.parentNode;
        }
        if (listItem && listItem.tagName === 'LI') {
            const ul = listItem.closest('ul, ol');
            if (ul) {
                ul.className = 'checkbox-list';
                const items = ul.querySelectorAll('li');
                items.forEach(li => {
                    const text = li.textContent.trim();
                    li.innerHTML = '';
                    const checkbox = document.createElement('input');
                    checkbox.type = 'checkbox';
                    const label = document.createElement('label');
                    label.textContent = text;
                    li.appendChild(checkbox);
                    li.appendChild(label);
                });
                if (window.notesEditor && window.notesEditor.getState) { /* ok */ }
                return;
            }
        }
        const ul = document.createElement('ul');
        ul.className = 'checkbox-list';
        const selectedText = selection.toString().trim();
        const lines = selectedText ? selectedText.split(/\r?\n/) : [''];
        lines.forEach((line, index) => {
            if (line.trim() === '' && index === 0 && lines.length === 1) line = 'Item';
            if (line.trim() !== '') {
                const li = document.createElement('li');
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                const label = document.createElement('label');
                label.textContent = line.trim();
                li.appendChild(checkbox);
                li.appendChild(label);
                ul.appendChild(li);
            }
        });
        if (ul.children.length === 0) {
            const li = document.createElement('li');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            const label = document.createElement('label');
            label.textContent = 'Item';
            li.appendChild(checkbox);
            li.appendChild(label);
            ul.appendChild(li);
        }
        range.deleteContents();
        range.insertNode(ul);
        const firstLabel = ul.querySelector('label');
        if (firstLabel) {
            const newRange = document.createRange();
            newRange.selectNodeContents(firstLabel);
            newRange.collapse(false);
            selection.removeAllRanges();
            selection.addRange(newRange);
        }
    }
})();
"""
