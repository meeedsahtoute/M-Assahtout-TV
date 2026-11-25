package com.streamtube.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.ui.AppRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            AppRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        lifecycleScope.launch {
            NativeBridge.resolveUrl(data).onSuccess { resolved ->
                StreamTubeApp.instance.emitRoute(resolved)
            }
        }
    }
}
