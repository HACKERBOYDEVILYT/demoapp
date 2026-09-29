package com.myra.assistant.ai

import android.content.Context
import android.media.*
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onPcmChunk: ((ByteArray) -> Unit)? = null

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val recording = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null
    private var recordThread: Thread? = null

    fun startPlayback() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        track?.play()
        playbackThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (track != null) {
                try {
                    val data = queue.take()
                    if (data.isEmpty()) continue
                    track?.write(data, 0, data.size)
                    if (queue.isEmpty() && speaking.compareAndSet(true, false)) onSpeakingStopped?.invoke()
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    fun queueAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (speaking.compareAndSet(false, true)) onSpeakingStarted?.invoke()
        queue.offer(pcm)
    }

    fun clearPlayback() {
        queue.clear()
        if (speaking.compareAndSet(true, false)) onSpeakingStopped?.invoke()
        track?.flush()
    }

    fun startRecording() {
        if (recording.get()) return
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        recorder?.startRecording()
        recording.set(true)
        recordThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(1024)
            while (recording.get()) {
                val n = recorder?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sum += sample * sample
                    i += 2
                }
                val rms = (sqrt(sum / (n / 2.0)) / 32768.0).toFloat().coerceIn(0f, 1f)
                onAmplitudeChanged?.invoke(rms)
                // Critical echo suppression: never send microphone PCM while MYRA is speaking.
                if (!muted.get() && !speaking.get()) onPcmChunk?.invoke(buf.copyOf(n))
            }
        }.apply { isDaemon = true; start() }
    }

    fun setMuted(value: Boolean) { muted.set(value) }
    fun isMuted() = muted.get()
    fun isSpeaking() = speaking.get()
    fun interrupt() = clearPlayback()

    fun release() {
        recording.set(false)
        recordThread?.interrupt()
        recorder?.runCatching { stop() }
        recorder?.release(); recorder = null
        clearPlayback()
        playbackThread?.interrupt()
        track?.runCatching { stop() }
        track?.release(); track = null
        queue.clear()
    }
}
