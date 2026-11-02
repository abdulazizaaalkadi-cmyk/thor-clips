package com.aziz.thorclips

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process

/**
 * Game-audio capture that runs inside the Shizuku helper, so it keeps going when
 * Android closes the Dual Clips app and needs no screen-capture prompt.
 *
 * Technique adapted from scrcpy's AudioPlaybackCapture (Apache License 2.0,
 * https://github.com/Genymobile/scrcpy): register an audio policy that loops back
 * what apps are playing (while still playing it on the device), and read it with an
 * AudioRecord. Apps that opt out of audio capture are still silent.
 */
class HelperAudio(
    private val ring: SampleRing,
    private val excludeUid: Int,
    private val voiceChat: Boolean,
    private val log: (String) -> Unit,
) {

    val ringRef: SampleRing get() = ring

    companion object {
        const val RATE = 48_000
        private const val SHELL = "com.android.shell"

        /** Makes framework audio classes happy in a bare app_process (as scrcpy does). */
        // Runs in the shell-level helper, where these internals are reachable (lint can't know that).
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
        fun prepareProcess(base: Context?) {
            try {
                val at = Class.forName("android.app.ActivityThread")
                var thread = at.getMethod("currentActivityThread").invoke(null)
                if (thread == null) {
                    val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
                    thread = ctor.newInstance()
                    at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
                    at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
                }
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    val cc = Class.forName("android.app.ConfigurationController")
                    val ati = Class.forName("android.app.ActivityThreadInternal")
                    val f = at.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                    if (f.get(thread) == null) {
                        f.set(thread, cc.getDeclaredConstructor(ati).apply { isAccessible = true }.newInstance(thread))
                    }
                }
                runCatching {
                    val bound = at.getDeclaredField("mBoundApplication").apply { isAccessible = true }
                    if (bound.get(thread) == null) {
                        val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                        val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        abd.getDeclaredField("appInfo").apply { isAccessible = true }
                            .set(data, ApplicationInfo().apply { packageName = SHELL })
                        bound.set(thread, data)
                    }
                }
                runCatching {
                    val initial = at.getDeclaredField("mInitialApplication").apply { isAccessible = true }
                    if (initial.get(thread) == null) {
                        val sys = base ?: at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
                        initial.set(thread, Instrumentation.newApplication(Application::class.java, ShellContext(sys)))
                    }
                }
            } catch (_: Throwable) {
                // Best effort: if this fails, creating the AudioRecord reports the real error.
            }
        }
    }

    /** Context that identifies as the shell, which holds the audio-routing permissions. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL).build()
            else super.getAttributionSource()
    }

    @Volatile var format: MediaFormat? = null
        private set
    @Volatile var level = 0.0
        private set
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @SuppressLint("PrivateApi")
    private fun createRecord(ctx: Context): AudioRecord {
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val rb = ruleB.getConstructor().newInstance()
        ruleB.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            .invoke(rb, ruleCls.getField("MIX_ROLE_PLAYERS").getInt(null))
        val matchUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val add = ruleB.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        val usages = mutableListOf(AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN)
        if (voiceChat) {
            // Friends talking in Discord etc. come out as "voice communication".
            usages += AudioAttributes.USAGE_VOICE_COMMUNICATION
            usages += AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
        }
        for (u in usages) add.invoke(rb, matchUsage, AudioAttributes.Builder().setUsage(u).build())
        if (voiceChat) runCatching {
            ruleB.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType).invoke(rb, true)
        }.onFailure { log("voice chat capture not allowed: ${it.message}") }
        // Leave Dual Clips' own chime out of clips.
        runCatching {
            ruleB.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(rb, ruleCls.getField("RULE_MATCH_UID").getInt(null), excludeUid)
        }
        val rule = ruleB.getMethod("build").invoke(rb)

        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mb = mixB.getConstructor(ruleCls).newInstance(rule)
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(
            mb, AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        )
        // LOOP_BACK_RENDER = capture *and* keep playing on the Thor's speakers.
        mixB.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mb, mixCls.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null))
        val mix = mixB.getMethod("build").invoke(mb)

        val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val pb = polB.getConstructor(Context::class.java).newInstance(ctx)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        val policy = polB.getMethod("build").invoke(pb)
        val reg = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", polCls).apply { isAccessible = true }
        val res = reg.invoke(null, policy) as Int
        if (res != 0) throw IllegalStateException("registerAudioPolicy returned $res")
        return polCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, mix) as AudioRecord
    }

    fun start(base: Context?) {
        prepareProcess(base)
        val ctx = ShellContext(base ?: Class.forName("android.app.ActivityThread").let { at ->
            at.getDeclaredMethod("getSystemContext").invoke(at.getMethod("currentActivityThread").invoke(null)) as Context
        })
        val rec = createRecord(ctx)
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
        thread = Thread({ loop(rec, enc) }, "hr-audio").apply { start() }
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(4096)
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
                // Track loudness so the log can say whether anything is actually heard.
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sumSq += v * v; count++; i += 8
                }
                if (count >= 48_000) { level = Math.sqrt(sumSq / count) / 32768.0; sumSq = 0.0; count = 0 }

                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    val len = minOf(n, ib.capacity())
                    ib.put(buf, 0, len)
                    enc.queueInputBuffer(inIdx, 0, len, t0 + frames * 1_000_000L / RATE, 0)
                    frames += len / 4
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
            log("audio loop ended: ${t.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        record = null
        codec = null
    }
}
