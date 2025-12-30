package dev.alsatianconsulting.transportchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.transportchat.ui.theme.TransportChatTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TransportChatTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AboutScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(onClose: () -> Unit) {
    val orange = Color(0xFFE87722)
    val githubUrl = "https://github.com/AlsatianConsulting"
    val version = "1.5.2"
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "TransportChat Android",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "Version $version",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "(c) 2025 Alsatian Consulting, LLC",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                    context.startActivity(intent)
                }
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = orange,
                contentColor = Color.Black
            )
        ) {
            Text("AlsatianConsulting on GitHub")
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(onClick = onClose) {
            Text("Close")
        }
    }
}
