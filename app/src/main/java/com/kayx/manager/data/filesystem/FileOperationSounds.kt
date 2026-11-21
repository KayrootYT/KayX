package com.kayx.manager.data.filesystem

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.kayx.manager.data.settings.SettingsStore

/** Small system-tone feedback; no audio files, network access, or runtime permissions required. */
enum class FileActionSound { COPY, MOVE, DELETE, RENAME, PASTE, EXTRACT, CREATE, SUCCESS }

object FileOperationSounds {
    private val main = Handler(Looper.getMainLooper())

    fun play(context: Context?, action: FileActionSound) {
        if (context == null || !SettingsStore.operationSounds) return
        val tone = when (action) {
            FileActionSound.COPY -> ToneGenerator.TONE_DTMF_1
            FileActionSound.MOVE -> ToneGenerator.TONE_DTMF_2
            FileActionSound.DELETE -> ToneGenerator.TONE_PROP_NACK
            FileActionSound.RENAME -> ToneGenerator.TONE_DTMF_5
            FileActionSound.PASTE -> ToneGenerator.TONE_DTMF_6
            FileActionSound.EXTRACT -> ToneGenerator.TONE_DTMF_8
            FileActionSound.CREATE -> ToneGenerator.TONE_PROP_BEEP
            FileActionSound.SUCCESS -> ToneGenerator.TONE_PROP_ACK
        }
        main.post {
            runCatching {
                val player = ToneGenerator(AudioManager.STREAM_SYSTEM, 45)
                player.startTone(tone, 85)
                main.postDelayed({ runCatching { player.release() } }, 250)
            }
        }
    }
}
