package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object NowContract {
    const val CORE_LOOP = "ASK / REFRESH → PROVE → KNOW → PAY"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NowApp()
        }
    }
}

@Composable
private fun NowApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "NOW Network",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "The Live Reality Network",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    modifier = Modifier.padding(top = 16.dp),
                    text = NowContract.CORE_LOOP,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
