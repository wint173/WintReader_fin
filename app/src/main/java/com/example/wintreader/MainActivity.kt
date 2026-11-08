package com.example.wintreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsStore = appContainer.settingsStore
        setContent {
            // アプリの見た目は設定（ライト・ダーク・端末に合わせる）に従う
            val prefs by settingsStore.appPrefs.collectAsState(initial = AppPrefs())
            WintTheme(prefs) {
                AppRoot()
            }
        }
    }
}
