package ru.protolink.communicator

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
        enableEdgeToEdge()
        setContent {
            ProtoLinkTheme {
                CommunicatorAppScreen()
            }
        }
    }
}
