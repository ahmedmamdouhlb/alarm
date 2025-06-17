package com.gdelataillade.alarm.services

import android.content.Context
import android.media.MediaPlayer
import com.gdelataillade.alarm.models.VolumeFadeStep
import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask
import io.flutter.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class AudioService(private val context: Context) {
    companion object {
        private const val TAG = "AudioService"
    }

    private val mediaPlayers = ConcurrentHashMap<Int, MediaPlayer>()
    private val timers = ConcurrentHashMap<Int, Timer>()
    private val autoStopTimers = ConcurrentHashMap<Int, Timer>()

    private var onAudioComplete: (() -> Unit)? = null

    fun setOnAudioCompleteListener(listener: () -> Unit) {
        onAudioComplete = listener
    }

    fun isMediaPlayerEmpty(): Boolean {
        return mediaPlayers.isEmpty()
    }

    fun getPlayingMediaPlayersIds(): List<Int> {
        return mediaPlayers.filter { (_, mediaPlayer) -> mediaPlayer.isPlaying }.keys.toList()
    }

    fun playAudio(
        id: Int,
        filePath: String,
        loopAudio: Boolean,
        fadeDuration: Duration?,
        fadeSteps: List<VolumeFadeStep>
    ) {
        stopAudio(id) // Stop and release any existing MediaPlayer and Timers for this ID

        val baseAppFlutterPath = context.filesDir.parent?.plus("/app_flutter/")
        val adjustedFilePath = when {
            filePath.startsWith("assets/") -> "flutter_assets/$filePath"
            !filePath.startsWith("/") -> baseAppFlutterPath + filePath
            else -> filePath
        }

        try {
            MediaPlayer().apply {
                when {
                    adjustedFilePath.startsWith("flutter_assets/") -> {
                        val assetManager = context.assets
                        val descriptor = assetManager.openFd(adjustedFilePath)
                        setDataSource(
                            descriptor.fileDescriptor,
                            descriptor.startOffset,
                            descriptor.length
                        )
                    }

                    else -> {
                        setDataSource(adjustedFilePath)
                    }
                }

                prepare()
                isLooping = loopAudio
                start()

                setOnCompletionListener {
                    if (!loopAudio) {
                        onAudioComplete?.invoke()
                    }
                }

                mediaPlayers[id] = this

                val fadeTimer = Timer(true)
                timers[id] = fadeTimer

                if (fadeSteps.isNotEmpty()) {
                    startStaircaseFadeIn(this, fadeSteps, fadeTimer)
                } else if (fadeDuration != null) {
                    startFadeIn(this, fadeDuration, fadeTimer)
                }

                // ⏲️ Auto-stop after 3 minutes
                autoStopTimers[id]?.cancel()
                val autoStopTimer = Timer(true)
                autoStopTimers[id] = autoStopTimer
                autoStopTimer.schedule(object : TimerTask() {
                    override fun run() {
                        stopAudio(id)
                        onAudioComplete?.invoke()
                    }
                }, 3 * 60 * 1000) // 3 minutes = 180000 ms
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error playing audio: $e")
        }
    }

    fun stopAudio(id: Int) {
        timers[id]?.cancel()
        timers.remove(id)

        autoStopTimers[id]?.cancel()
        autoStopTimers.remove(id)

        mediaPlayers[id]?.apply {
            if (isPlaying) {
                stop()
            }
            reset()
            release()
        }
        mediaPlayers.remove(id)
    }

    private fun startFadeIn(mediaPlayer: MediaPlayer, duration: Duration, timer: Timer) {
        val maxVolume = 1.0f
        val fadeDuration = duration.inWholeMilliseconds
        val fadeInterval = 100L
        val numberOfSteps = fadeDuration / fadeInterval
        val deltaVolume = maxVolume / numberOfSteps
        var volume = 0.0f

        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!mediaPlayer.isPlaying) {
                    cancel()
                    return
                }

                mediaPlayer.setVolume(volume, volume)
                volume += deltaVolume

                if (volume >= maxVolume) {
                    mediaPlayer.setVolume(maxVolume, maxVolume)
                    cancel()
                }
            }
        }, 0, fadeInterval)
    }

    private fun startStaircaseFadeIn(
        mediaPlayer: MediaPlayer,
        steps: List<VolumeFadeStep>,
        timer: Timer
    ) {
        val fadeIntervalMillis = 100L
        var currentStep = 0

        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!mediaPlayer.isPlaying) {
                    cancel()
                    return
                }

                val currentTime = (currentStep * fadeIntervalMillis).milliseconds
                val nextIndex = steps.indexOfFirst { it.time >= currentTime }

                if (nextIndex < 0) {
                    cancel()
                    return
                }

                val nextVolume = steps[nextIndex].volume
                var currentVolume = nextVolume

                if (nextIndex > 0) {
                    val prevTime = steps[nextIndex - 1].time
                    val nextTime = steps[nextIndex].time
                    val nextRatio = (currentTime - prevTime) / (nextTime - prevTime)

                    val prevVolume = steps[nextIndex - 1].volume
                    currentVolume = nextVolume * nextRatio + prevVolume * (1 - nextRatio)
                }

                mediaPlayer.setVolume(currentVolume.toFloat(), currentVolume.toFloat())
                currentStep++
            }
        }, 0, fadeIntervalMillis)
    }

    fun cleanUp() {
        timers.values.forEach(Timer::cancel)
        timers.clear()

        autoStopTimers.values.forEach(Timer::cancel)
        autoStopTimers.clear()

        mediaPlayers.values.forEach { mediaPlayer ->
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer.reset()
            mediaPlayer.release()
        }
        mediaPlayers.clear()
    }
}
