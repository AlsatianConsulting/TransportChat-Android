package com.example.lanchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lanchat.net.ChatClient
import com.example.lanchat.net.ChatServer
import com.example.lanchat.net.NsdDiscovery
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var server: ChatServer
    private lateinit var discovery: NsdDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        server = ChatServer(0, lifecycleScope)
        server.start()

        val name = "LanChat-${android.os.Build.MODEL.take(8)}-${(1000..9999).random()}"
        discovery = NsdDiscovery(this, name) { server.localPort }
        discovery.register()
        discovery.startDiscovery()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val peers by discovery.peers.collectAsState()
                    val incoming by server.incoming.collectAsState()
                    var text by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()

                    Column(Modifier.padding(16.dp)) {
                        Text("LAN-only Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("This device: $name @ port ${server.localPort}")
                        Spacer(Modifier.height(12.dp))

                        Text("Peers", fontWeight = FontWeight.Bold)
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(peers) { p ->
                                ListItem(
                                    headlineText = { Text(p.name) },
                                    supportingText = { Text("${p.host}:${p.port}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Divider()
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Message") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            val p = peers.firstOrNull()
                            if (p != null && text.isNotBlank()) {
                                scope.launch {
                                    ChatClient().send(p.host, p.port, text)
                                }
                                text = ""
                            }
                        }, enabled = peers.isNotEmpty() && text.isNotBlank()) {
                            Text("Send to first peer")
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("Incoming messages", fontWeight = FontWeight.Bold)
                        LazyColumn {
                            items(incoming) { m -> Text(m) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stopDiscovery()
        discovery.unregister()
        server.stop()
    }
}
