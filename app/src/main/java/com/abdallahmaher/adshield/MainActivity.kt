package com.abdallahmaher.adshield

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var isRunning = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning.value = AdVpnService.isRunning

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showEFootballSettings by remember { mutableStateOf(false) }
                    if (showEFootballSettings) {
                        EFootballSettingsScreen(
                            context = this@MainActivity,
                            onBack = { showEFootballSettings = false }
                        )
                    } else {
                        val running by isRunning
                        HomeScreen(
                            isRunning = running,
                            onToggle = { toggleVpn() },
                            onOpenEFootballSettings = { showEFootballSettings = true }
                        )
                    }
                }
            }
        }
    }

    private fun toggleVpn() {
        if (isRunning.value) {
            stopVpn()
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_START)
        startService(intent)
        isRunning.value = true
    }

    private fun stopVpn() {
        val intent = Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_STOP)
        startService(intent)
        isRunning.value = false
    }
}

@Composable
fun HomeScreen(isRunning: Boolean, onToggle: () -> Unit, onOpenEFootballSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isRunning) "الحماية شغالة ✅" else "الحماية متوقفة",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onToggle) {
            Text(if (isRunning) "إيقاف حجب الإعلانات" else "تشغيل حجب الإعلانات")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "بيشتغل عن طريق فلترة DNS محليًا على الجهاز نفسه، على مستوى النظام كله، " +
                "من غير ما ترفع أي بيانات لسيرفر خارجي.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(onClick = onOpenEFootballSettings) {
            Text("⚙️ خاص بـ eFootball™")
        }
    }
}
