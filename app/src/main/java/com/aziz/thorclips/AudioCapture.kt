package com.aziz.thorclips

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log

/**
 * Captures game audio (what's playing on the device, not the microphone) and keeps
 * it as AAC in its own ring. Some apps forbid other apps from recording their sound;
 * those will simply be silent in clips.
 */
class AudioCapture(private val projection: MediaProjection, val ring: SampleRing) {

    @Volatile var format: MediaFormat? = null
        private set

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val pcm = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(pcm)
            .setBufferSizeInBytes(maxOf(minBuf * 2, 32768))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        rec.startRecording()

        record = rec
        codec = enc
        running = true
        thread = Thread({ loop(rec, enc) }, "tc-audio").apply { start() }
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var t0 = -1L
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (t0 < 0) t0 = System.nanoTime() / 1000
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    ib.put(buf, 0, minOf(n, ib.capacity()))
                    val pts = t0 + frames * 1_000_000L / RATE
                    enc.queueInputBuffer(inIdx, 0, minOf(n, ib.capacity()), pts, 0)
                    frames += n / 4 // 16-bit stereo = 4 bytes per frame
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = enc.outputFormat
                        continue
                    }
                    if (outIdx < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val ob = enc.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ring.add(ob, info.presentationTimeUs, true)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (t: Throwable) {
            Log.w("ThorClips", "audio loop ended", t)
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        record = null
        codec = null
    }

    companion object {
        const val RATE = 48_000
    }
}
