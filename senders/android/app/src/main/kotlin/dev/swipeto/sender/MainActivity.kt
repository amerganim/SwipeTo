package dev.swipeto.sender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.swipeto.sender.ui.SwipeToScreen
import dev.swipeto.sender.ui.SwipeToTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwipeToTheme {
                SwipeToScreen(viewModel)
            }
        }
    }
}
