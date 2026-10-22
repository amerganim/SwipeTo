package dev.swipeto.sender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.swipeto.sender.ui.SwipeToScreen
import dev.swipeto.sender.ui.SwipeToTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel get() = (application as SwipeToApp).engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwipeToTheme {
                SwipeToScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForeground(true)
    }

    override fun onPause() {
        viewModel.setForeground(false)
        super.onPause()
    }
}
