package ru.protolink.communicator.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * Capture/restore Notes editor scroll + caret across same-page content refresh.
 * Prefer in-place #editor update (keeps document listeners) over loadData remount.
 * Mirrors ProtoLink.Communicator.Windows.Utilities.NotesEditorViewport.
 */
object NotesEditorViewport {
    // Match Win UnsafeRelaxedJsonEscaping: keep <>& in HTML payloads (still JSON-quoted).
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    data class State(
        val scrollY: Double,
        val caretStart: Int?,
        val caretEnd: Int?
    )

    fun buildCaptureScript(): String = """
(function(){
var editor=document.getElementById('editor');
var scrollY=window.scrollY||document.documentElement.scrollTop||document.body.scrollTop||0;
var caretStart=null,caretEnd=null;
if(editor){
var sel=window.getSelection();
if(sel&&sel.rangeCount>0){
var range=sel.getRangeAt(0);
if(editor.contains(range.commonAncestorContainer)){
function offsetInEditor(node,offset){
var pre=document.createRange();
pre.selectNodeContents(editor);
pre.setEnd(node,offset);
return pre.toString().length;
}
try{
caretStart=offsetInEditor(range.startContainer,range.startOffset);
caretEnd=offsetInEditor(range.endContainer,range.endOffset);
}catch(e){}
}
}
}
return JSON.stringify({scrollY:scrollY,caretStart:caretStart,caretEnd:caretEnd});
})();
""".trimIndent()

    fun buildApplyInnerHtmlScript(innerHtml: String, viewport: State?): String {
        val htmlJson = gson.toJson(innerHtml)
        val scroll = (viewport?.scrollY ?: 0.0).toString()
        val start = viewport?.caretStart?.toString() ?: "null"
        val end = viewport?.caretEnd?.toString() ?: "null"
        return """
(function(){
var editor=document.getElementById('editor');
if(!editor)return false;
editor.innerHTML=$htmlJson;
editor.querySelectorAll('ul.checkbox-list li label[for]').forEach(function(label){
label.removeAttribute('for');
});
var scrollY=$scroll;
var caretStart=$start;
var caretEnd=$end;
window.scrollTo(0,scrollY);
if(document.documentElement)document.documentElement.scrollTop=scrollY;
if(document.body)document.body.scrollTop=scrollY;
if(caretStart!=null){
var targetEnd=caretEnd!=null?caretEnd:caretStart;
var walker=document.createTreeWalker(editor,NodeFilter.SHOW_TEXT,null);
var pos=0,startNode=null,startOff=0,endNode=null,endOff=0;
while(walker.nextNode()){
var n=walker.currentNode;
var len=(n.textContent||'').length;
if(startNode==null&&pos+len>=caretStart){startNode=n;startOff=caretStart-pos;}
if(endNode==null&&pos+len>=targetEnd){endNode=n;endOff=targetEnd-pos;break;}
pos+=len;
}
if(startNode){
try{
var r=document.createRange();
var sn=startNode,so=Math.min(startOff,(sn.textContent||'').length);
var en=endNode||startNode,eo=Math.min(endOff,((endNode||startNode).textContent||'').length);
r.setStart(sn,so);
r.setEnd(en,eo);
var sel=window.getSelection();
sel.removeAllRanges();
sel.addRange(r);
editor.focus();
}catch(err){}
}
}
return true;
})();
""".trimIndent()
    }

    fun buildRestoreScript(viewport: State): String {
        val scroll = viewport.scrollY.toString()
        val start = viewport.caretStart?.toString() ?: "null"
        val end = viewport.caretEnd?.toString() ?: "null"
        return """
(function(){
var editor=document.getElementById('editor');
var scrollY=$scroll;
var caretStart=$start;
var caretEnd=$end;
window.scrollTo(0,scrollY);
if(document.documentElement)document.documentElement.scrollTop=scrollY;
if(document.body)document.body.scrollTop=scrollY;
if(!editor||caretStart==null)return;
var targetEnd=caretEnd!=null?caretEnd:caretStart;
var walker=document.createTreeWalker(editor,NodeFilter.SHOW_TEXT,null);
var pos=0,startNode=null,startOff=0,endNode=null,endOff=0;
while(walker.nextNode()){
var n=walker.currentNode;
var len=(n.textContent||'').length;
if(startNode==null&&pos+len>=caretStart){startNode=n;startOff=caretStart-pos;}
if(endNode==null&&pos+len>=targetEnd){endNode=n;endOff=targetEnd-pos;break;}
pos+=len;
}
if(!startNode)return;
try{
var r=document.createRange();
r.setStart(startNode,Math.min(startOff,(startNode.textContent||'').length));
r.setEnd(endNode||startNode,Math.min(endOff,((endNode||startNode).textContent||'').length));
var sel=window.getSelection();
sel.removeAllRanges();
sel.addRange(r);
editor.focus();
}catch(err){}
})();
""".trimIndent()
    }

    /** Parse WebView evaluateJavascript JSON-encoded return value from [buildCaptureScript]. */
    fun parseCaptureResult(raw: String?): State? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val payload = decodeEvaluateJavascriptString(raw).ifBlank {
            // Capture may return a bare JSON object when not double-encoded.
            raw.trim().removeSurrounding("\"")
        }
        if (payload.isBlank() || payload == "null") return null
        return runCatching {
            val root = JsonParser.parseString(payload).asJsonObject
            val scrollY = root.get("scrollY")?.asDouble ?: 0.0
            val caretStart = root.get("caretStart")?.takeIf { !it.isJsonNull }?.asInt
            val caretEnd = root.get("caretEnd")?.takeIf { !it.isJsonNull }?.asInt
            State(scrollY, caretStart, caretEnd)
        }.getOrNull()
    }

    fun isApplySuccess(raw: String?): Boolean {
        val decoded = decodeEvaluateJavascriptString(raw)
        return raw == "true" || raw == "\"true\"" || decoded == "true"
    }
}
