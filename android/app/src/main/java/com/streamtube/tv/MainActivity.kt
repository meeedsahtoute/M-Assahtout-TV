package com.streamtube.tv

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.ui.AppRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setBackgroundDrawable(ColorDrawable(0xFF0F0F0F.toInt()))
        window.statusBarColor = 0xFF0F0F0F.toInt()
        window.navigationBarColor = 0xFF0F0F0F.toInt()
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot()
        }
        handleIntent(intent)
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
