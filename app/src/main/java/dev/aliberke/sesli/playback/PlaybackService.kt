package dev.aliberke.sesli.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.aliberke.sesli.MainActivity
import dev.aliberke.sesli.ProseodyApp
import dev.aliberke.sesli.tts.SpeechEngine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Exposes the speech engine as a Media3 player so the app gets a media notification,
 * lock-screen and headset controls, and stays alive in the background while reading.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(looper: Looper, private val engine: SpeechEngine) : SimpleBasePlayer(looper) {
    private val scope = MainScope()

    init {
        scope.launch {
            engine.state
                .map { listOf(it.docId, it.title, it.playing, it.position / 40, it.length, it.rate) }
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
    }

    private fun msPerChar(rate: Float) = 1000.0 / (SpeechEngine.CHARS_PER_SECOND * rate)

    override fun getState(): State {
        val s = engine.state.value
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA, COMMAND_RELEASE,
        ).build()
        val docId = s.docId ?: return State.Builder().setAvailableCommands(commands).setPlaybackState(STATE_IDLE).build()
        val perChar = msPerChar(s.rate)
        val metadata = MediaMetadata.Builder().setTitle(s.title).setArtist("Proseody").build()
        val item = MediaItemData.Builder(docId)
            .setMediaItem(MediaItem.Builder().setMediaId(docId.toString()).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs((s.length * perChar).toLong() * 1000)
            .setIsSeekable(true)
            .build()
        val ended = !s.playing && s.length > 0 && s.position >= s.length
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (ended) STATE_ENDED else STATE_READY)
            .setContentPositionMs((s.position * perChar).toLong())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_FORWARD -> engine.nextSentence()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_BACK -> engine.previousSentence()
            else -> if (positionMs != C.TIME_UNSET) engine.seekTo((positionMs / msPerChar(engine.state.value.rate)).toInt())
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    private val engine get() = (application as ProseodyApp).engine

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val engine = engine
        // A single document is one media item, so route next/previous to sentence skipping.
        val player = object : ForwardingPlayer(TtsPlayer(mainLooper, engine)) {
            override fun seekToNext() = engine.nextSentence()
            override fun seekToPrevious() = engine.previousSentence()
            override fun seekToNextMediaItem() = engine.nextSentence()
            override fun seekToPreviousMediaItem() = engine.previousSentence()
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!engine.state.value.playing) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
