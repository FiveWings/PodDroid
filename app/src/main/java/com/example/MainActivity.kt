package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.example.media.PodcastMediaService
import com.example.ui.PodBridgeApp
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.PodBridgeViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PodBridgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PodcastMediaService.ensureStarted(applicationContext)

        intent?.dataString?.let { deepLinkUrl ->
            if (deepLinkUrl.contains("podcasts.apple.com")) {
                viewModel.navigateToUrl(deepLinkUrl)
            }
        }

        setContent {
            MyApplicationTheme {
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { /* Media notification displays when granted */ }
                )

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                PodBridgeApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { deepLinkUrl ->
            if (deepLinkUrl.contains("podcasts.apple.com")) {
                viewModel.navigateToUrl(deepLinkUrl)
            }
        }
    }
}
