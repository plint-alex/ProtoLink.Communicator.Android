package ru.protolink.communicator.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FAIL class P1-20261117 Force after heal: SafLocalFileSystem.find fell back to
 * DocumentFile.listFiles on SingleDocumentFile → UnsupportedOperationException abort.
 */
class SafChildResolveTest {

    @Test
    fun nextChild_prefersListedMatch_caseInsensitive() {
        val listed = listOf(
            "Films" to "root/Films",
            "plains" to "root/plains"
        )
        val id = SafChildResolve.nextChildDocumentId(listed, "films", "root") { false }
        assertThat(id).isEqualTo("root/Films")
    }

    @Test
    fun nextChild_guessesWhenListBlind_oemLeaf() {
        val id = SafChildResolve.nextChildDocumentId(
            emptyList<Pair<String, String>>(),
            "AgentTeamVerify",
            "root/MyNotes"
        ) { guess -> guess == "root/MyNotes/AgentTeamVerify" }
        assertThat(id).isEqualTo("root/MyNotes/AgentTeamVerify")
    }

    @Test
    fun nextChild_returnsNullWhenMissing() {
        val id = SafChildResolve.nextChildDocumentId(
            listOf("other" to "root/other"),
            "Films",
            "root"
        ) { false }
        assertThat(id).isNull()
    }

    @Test
    fun nextChild_neverNeedsListFilesFallback() {
        // Contract: resolution uses listed + guess only — callers must not call listFiles.
        val path = listOf("Films", "index.html")
        var cur = "primary:MyNotesFolder"
        val listedByParent = mapOf(
            "primary:MyNotesFolder" to listOf("Films" to "primary:MyNotesFolder/Films"),
            "primary:MyNotesFolder/Films" to emptyList<Pair<String, String>>()
        )
        for (part in path) {
            val next = SafChildResolve.nextChildDocumentId(
                listedByParent[cur].orEmpty(),
                part,
                cur
            ) { guess -> guess.endsWith("/index.html") }
            assertThat(next).isNotNull()
            cur = next!!
        }
        assertThat(cur).isEqualTo("primary:MyNotesFolder/Films/index.html")
    }
}
