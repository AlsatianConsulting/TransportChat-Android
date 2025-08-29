package dev.alsatianconsulting.transportchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.ui.theme.TransportChatTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)

        setContent {
            TransportChatTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onClose: () -> Unit
) {
    val displayName by AppSettings.displayName.collectAsState()
    val listenPort by AppSettings.listenPort.collectAsState()

    var nameText by remember(displayName) { mutableStateOf(displayName) }
    var portText by remember(listenPort) { mutableStateOf(listenPort.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }

        Text(
            "Set your Display Name and the TCP port the server listens on. " +
                    "Only these settings are saved; chats remain in-memory and clear when the app closes.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            label = { Text("Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { txt -> portText = txt.filter { it.isDigit() }.take(5) },
            label = { Text("Listen Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                val name = nameText.trim()
                val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: AppSettings.listenPortValue

                AppSettings.updateDisplayName(name)
                AppSettings.updateListenPort(port)

                onClose()
            }) { Text("Save") }

            OutlinedButton(onClick = {
                nameText = displayName
                portText = listenPort.toString()
            }) { Text("Reset") }
        }
    }
}
