package io.github.illagercpr.landrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.illagercpr.landrop.ui.home.HomeScreen
import io.github.illagercpr.landrop.ui.theme.LanDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LanDropTheme {
                HomeScreen()
            }
        }
    }
}
