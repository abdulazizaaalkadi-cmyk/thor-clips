package com.aziz.thorclips

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mixes your mic into a clip at save time.
 *
 * Output tracks: video, [game + voice chat + your mic] (plays by default), and the
 * original [game + voice chat] as a second audio track. "Remove my voice" in My Clips
 * just keeps the second one, with no quality loss.
 */
object AudioMixer {

    private const val RATE = 48_000

    /** 16-bit stereo PCM at 48 kHz, starting at [startUs] (relative to the clip start). */
    class Pcm(val data: ShortArray, val startUs: Long)

    class Encoded(val bytes: ByteArray, val ptsUs: Long)

    // ------------------------------------------------------------------ decode

    private fun decode(format: MediaFormat, feed: (ByteBuffer) -> Pair<Int, Long>?): Pcm? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(format, null, null, 0)
        dec.start()
        val out = ShortArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var firstUs = -1L
        var inputDone = false
        var idle = 0
        try {
            while (true) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val ib = dec.getInputBuffer(idx)!!
                        ib.clear()
                        val r = feed(ib)
                        if (r == null) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, r.first, r.second, 0)
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (o < 0) {
                    // After all input is in, give the decoder ~1 s to flush, then stop.
                    if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER && ++idle > 200) break
                    continue
                }
                idle = 0
                if (info.size > 0) {
                    if (firstUs < 0) firstUs = info.presentationTimeUs
                    val sb = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = info.size / 2
                    val tmp = ShortArray(n)
                    sb.position(info.offset / 2)
                    sb.get(tmp, 0, n)
                    if (channels == 1) for (v in tmp) { out.add(v); out.add(v) } // mono → stereo
                    else { var i = 0; while (i + 1 < n) { out.add(tmp[i]); out.add(tmp[i + 1]); i += channels } }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                dec.releaseOutputBuffer(o, false)
                if (eos) break
            }
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }
        return Pcm(out.build(), maxOf(firstUs, 0))
    }

    /** Decodes an audio track of a file. */
    fun decodeTrack(file: File, track: Int): Pcm? {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        ex.selectTrack(track)
        return try {
            decode(ex.getTrackFormat(track)) { buf ->
                val n = ex.readSampleData(buf, 0)
                if (n < 0) null else (n to ex.sampleTime).also { ex.advance() }
            }
        } finally {
            ex.release()
        }
    }

    /** Decodes pinned ring samples; times are made relative to [base]. */
    fun decodeRing(format: MediaFormat, ring: SampleRing, samples: List<SampleRing.Sample>, base: Long): Pcm? {
        val usable = samples.filter { it.ptsUs >= base }
        if (usable.isEmpty()) return null
        var i = 0
        val tmp = ByteBuffer.allocateDirect(64 * 1024)
        return decode(format) { buf ->
            if (i >= usable.size) null else {
                val s = usable[i++]
                ring.read(s, tmp)
                buf.put(tmp)
                s.size to (s.ptsUs - base)
            }
        }
    }

    // ------------------------------------------------------------------ mix + encode

    /** game (may be null) + mic * gain, clipped to 16-bit, encoded as stereo AAC. */
    fun mixAndEncode(game: Pcm?, mic: Pcm?, micGain: Float, lengthUs: Long): Pair<MediaFormat, List<Encoded>> {
        val frames = (lengthUs * RATE / 1_000_000L).toInt().coerceAtLeast(1)
        val mixed = ShortArray(frames * 2)
        fun addIn(p: Pcm?, gain: Float) {
            p ?: return
            val offset = (p.startUs * RATE / 1_000_000L).toInt() * 2
            for (k in p.data.indices) {
                val j = offset + k
                if (j < 0 || j >= mixed.size) continue
                val v = mixed[j] + (p.data[k] * gain).toInt()
                mixed[j] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        addIn(game, 1f)
        addIn(mic, micGain)

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = ArrayList<Encoded>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0 // in shorts
        var inputDone = false
        var idle = 0
        try {
            while (true) {
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val ib = enc.getInputBuffer(idx)!!.order(ByteOrder.nativeOrder())
                        ib.clear()
                        val n = minOf(mixed.size - pos, ib.capacity() / 2, 2048 * 2)
                        val pts = (pos / 2).toLong() * 1_000_000L / RATE
                        if (n <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.asShortBuffer().put(mixed, pos, n)
                            enc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = enc.outputFormat; continue }
                if (o < 0) { if (inputDone && ++idle > 200) break; continue }
                idle = 0
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val ob = enc.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val b = ByteArray(info.size); ob.get(b)
                    out += Encoded(b, info.presentationTimeUs)
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (eos) break
            }
        } finally {
            runCatching { enc.stop() }
            runCatching { enc.release() }
        }
        return (outFormat ?: enc.outputFormat) to out
    }

    // ------------------------------------------------------------------ final file

    /**
     * Writes video + mixed audio (+ the original game audio as a second track) into [fd].
     * Returns the clip length in µs.
     */
    fun writeWithMic(
        source: File, videoTrack: Int, gameTrack: Int,
        mixedFormat: MediaFormat, mixed: List<Encoded>, fd: FileDescriptor,
    ): Long {
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val exV = MediaExtractor().apply { setDataSource(source.absolutePath); selectTrack(videoTrack) }
        val exA = if (gameTrack >= 0) MediaExtractor().apply { setDataSource(source.absolutePath); selectTrack(gameTrack) } else null
        var lastUs = 0L
        try {
            val tV = muxer.addTrack(exV.getTrackFormat(videoTrack))
            val tMix = muxer.addTrack(mixedFormat)
            val tGame = if (exA != null) muxer.addTrack(exA.getTrackFormat(gameTrack)) else -1
            muxer.start()
            val buf = ByteBuffer.allocateDirect(4 shl 20)
            val info = MediaCodec.BufferInfo()
            var mi = 0
            var vDone = false
            var aDone = exA == null
            while (!vDone || !aDone || mi < mixed.size) {
                val vT = if (vDone) Long.MAX_VALUE else exV.sampleTime.let { if (it < 0) { vDone = true; Long.MAX_VALUE } else it }
                val aT = if (aDone) Long.MAX_VALUE else exA!!.sampleTime.let { if (it < 0) { aDone = true; Long.MAX_VALUE } else it }
                val mT = if (mi < mixed.size) mixed[mi].ptsUs else Long.MAX_VALUE
                val minT = minOf(vT, aT, mT)
                if (minT == Long.MAX_VALUE) break
                buf.clear()
                when (minT) {
                    vT -> {
                        val n = exV.readSampleData(buf, 0)
                        val key = exV.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, n, vT, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(tV, buf, info)
                        lastUs = vT
                        exV.advance()
                    }
                    aT -> {
                        val n = exA!!.readSampleData(buf, 0)
                        info.set(0, n, aT, 0)
                        muxer.writeSampleData(tGame, buf, info)
                        exA.advance()
                    }
                    else -> {
                        val e = mixed[mi++]
                        buf.put(e.bytes); buf.flip()
                        info.set(0, e.bytes.size, e.ptsUs, 0)
                        muxer.writeSampleData(tMix, buf, info)
                    }
                }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { exV.release() }
            runCatching { exA?.release() }
        }
        return lastUs
    }

    /** Copies video + the second audio track only (the clip without your mic) into [fd]. */
    fun writeWithoutMic(source: File, fd: FileDescriptor): Boolean {
        val probe = MediaExtractor().apply { setDataSource(source.absolutePath) }
        var v = -1
        val audios = ArrayList<Int>()
        for (t in 0 until probe.trackCount) {
            val m = probe.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") && v < 0) v = t
            if (m.startsWith("audio/")) audios += t
        }
        probe.release()
        if (v < 0 || audios.size < 2) return false
        val keep = listOf(v, audios[1])
        val ex = MediaExtractor().apply { setDataSource(source.absolutePath) }
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val map = HashMap<Int, Int>()
            for (t in keep) { ex.selectTrack(t); map[t] = muxer.addTrack(ex.getTrackFormat(t)) }
            muxer.start()
            val buf = ByteBuffer.allocateDirect(4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val t = ex.sampleTrackIndex
                if (t < 0) break
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map[t]!!, buf, info)
                ex.advance()
            }
            muxer.stop()
            return true
        } finally {
            runCatching { muxer.release() }
            runCatching { ex.release() }
        }
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(1 shl 16)
        private var n = 0
        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun build(): ShortArray = a.copyOf(n)
    }
}
