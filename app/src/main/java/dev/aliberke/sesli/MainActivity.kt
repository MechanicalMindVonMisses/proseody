package dev.aliberke.sesli

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.aliberke.sesli.playback.PlaybackService
import dev.aliberke.sesli.ui.ProseodyRoot
import dev.aliberke.sesli.ui.theme.ProseodyTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_PLAYER = "dev.aliberke.sesli.OPEN_PLAYER"
    }

    private val vm: MainViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProseodyTheme {
                ProseodyRoot(vm)
            }
        }
        if (savedInstanceState == null) vm.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts the media session service, which then takes over
        // the notification and foreground state whenever the engine is playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }
}
