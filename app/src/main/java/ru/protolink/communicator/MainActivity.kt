package ru.protolink.communicator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.protolink.communicator.ui.CommunicatorAppScreen
import ru.protolink.communicator.ui.theme.ProtoLinkTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContent so ViewModel init can see the pending force flag.
        PendingDebugIntent.consumeForceFile(applicationContext)
        PendingDebugIntent.consumeCreateNoteFile(applicationContext)
        PendingDebugIntent.consumeDeleteNoteFile(applicationContext)
        PendingDebugIntent.consumeFrom(intent)
        enableEdgeToEdge()
        setContent {
            ProtoLinkTheme {
                CommunicatorAppScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingDebugIntent.consumeForceFile(applicationContext)
        PendingDebugIntent.consumeCreateNoteFile(applicationContext)
        PendingDebugIntent.consumeDeleteNoteFile(applicationContext)
        PendingDebugIntent.consumeFrom(intent)
    }
}
