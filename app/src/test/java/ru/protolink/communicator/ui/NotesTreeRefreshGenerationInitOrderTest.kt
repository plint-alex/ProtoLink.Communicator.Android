package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Documents the Kotlin class-body init order that crashed Communicator Android 1.0.71:
 * [init] may call a method that touches fields declared later in the class body.
 * Those fields are still null → NPE (direct access from init is a compile error;
 * the ViewModel path is init → refreshNotesTree → refreshNotesTreeSuspend → incrementAndGet).
 *
 * Production fix: declare notesTreeRefreshGeneration (+ pendingCreatedNotes) above init
 * in [MainViewModel].
 */
class NotesTreeRefreshGenerationInitOrderTest {

    @Test
    fun generationUsableFromInit_whenDeclaredAboveInit() {
        class Host {
            private val generation = AtomicInteger(0)
            val fromInit: Int

            init {
                fromInit = bump()
            }

            private fun bump(): Int = generation.incrementAndGet()
        }

        assertThat(Host().fromInit).isEqualTo(1)
    }

    @Test
    fun generationNpeFromInit_whenDeclaredBelowInit_viaMethod() {
        class Broken {
            init {
                bump()
            }

            private fun bump(): Int = generation.incrementAndGet()

            private val generation = AtomicInteger(0)
        }

        assertThrows(NullPointerException::class.java) {
            Broken()
        }
    }

    @Test
    fun pendingMapUsableFromInit_whenDeclaredAboveInit() {
        class Host {
            private val pending = java.util.concurrent.ConcurrentHashMap<String, String>()
            val sizeFromInit: Int

            init {
                sizeFromInit = register()
            }

            private fun register(): Int {
                pending["a"] = "1"
                return pending.size
            }
        }

        assertThat(Host().sizeFromInit).isEqualTo(1)
    }
}
