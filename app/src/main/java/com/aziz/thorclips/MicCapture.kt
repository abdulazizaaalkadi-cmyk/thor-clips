package com.aziz.thorclips

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process

/**
 * Records your microphone into its own ring (AAC, mono, 48 kHz). It's mixed into a clip
 * only when the clip is saved, so the clip also keeps a copy of the audio without your voice.
 */
class MicCapture(val ring: SampleRing) {

    @Volatile var format: MediaFormat? = null
        private set
    @Volatile var level = 0.0
        private set
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION shares the mic best with voice-chat apps and cancels echo from the speakers.
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        rec.startRecording()
        record = rec
        codec = enc
        running = true
        thread = Thread({ loop(rec, enc) }, "tc-mic").apply { start() }
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var t0 = -1L
        var sumSq = 0.0
        var count = 0L
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (t0 < 0) t0 = System.nanoTime() / 1000
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sumSq += v * v; count++; i += 4
                }
                if (count >= 24_000) { level = Math.sqrt(sumSq / count) / 32768.0; sumSq = 0.0; count = 0 }
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    val len = minOf(n, ib.capacity())
                    ib.put(buf, 0, len)
                    enc.queueInputBuffer(inIdx, 0, len, t0 + frames * 1_000_000L / RATE, 0)
                    frames += len / 2
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format = enc.outputFormat; continue }
                    if (o < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val ob = enc.getOutputBuffer(o)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        ring.add(ob, info.presentationTimeUs, true)
                    }
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } catch (t: Throwable) {
            EventLog.add("mic loop ended: ${t.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    companion object {
        const val RATE = 48_000
    }
}
