package app.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AppState(applicationContext)
        setContent {
            EchoApp(remember { state })
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时重算 7 天失效标记，长开进程不会一直显示旧状态（P2-8）。
        if (::state.isInitialized) state.refreshExpired()
    }
}
