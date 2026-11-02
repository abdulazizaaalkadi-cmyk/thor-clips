package com.aziz.thorclips

import java.nio.ByteBuffer

/**
 * Fixed-size ring of already-encoded samples (video or audio).
 *
 * Memory is reserved once (off the Java heap) and reused forever, so usage stays
 * flat however long you play. The oldest samples are overwritten as new ones arrive.
 *
 * While a clip is being saved, the samples in that clip are "pinned" so they can't be
 * overwritten mid-save. If the ring fills up during a save, new samples are dropped
 * until the next keyframe instead (a tiny gap in the *next* clip, never a broken file).
 */
class SampleRing(capacityBytes: Int, private val windowUs: Long, storage: ByteBuffer? = null) {

    companion object {
        private var generation = 0

        /**
         * A ring stored in a memory-mapped file instead of RAM. Android can flush those
         * pages to storage under memory pressure instead of killing Dual Clips, which
         * matters next to heavy emulators like Eden.
         */
        fun fileBacked(dir: java.io.File, name: String, capacityBytes: Int, windowUs: Long): SampleRing {
            return try {
                // Alternate files so a clip still being saved isn't overwritten by a rebuilt ring.
                val f = java.io.File(dir, "ring_${name}_${(generation++) % 2}.bin")
                val raf = java.io.RandomAccessFile(f, "rw")
                raf.setLength(capacityBytes.toLong())
                val map = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, capacityBytes.toLong())
                raf.close() // the mapping stays valid after closing the file
                SampleRing(capacityBytes, windowUs, map)
            } catch (t: Throwable) {
                SampleRing(capacityBytes, windowUs)
            }
        }
    }

    class Sample(val offset: Int, val size: Int, val ptsUs: Long, val key: Boolean)

    private val cap = capacityBytes
    private val data: ByteBuffer = storage ?: ByteBuffer.allocateDirect(capacityBytes)
    private val samples = ArrayDeque<Sample>()
    private var head = 0
    private var used = 0

    private var pinned = false
    private var evictableBeforePin = 0
    private var dropping = false

    /** Called when samples had to be dropped; the encoder should emit a keyframe soon. */
    var onNeedKeyframe: (() -> Unit)? = null

    val capacity: Int get() = cap

    private fun canEvictFront(): Boolean {
        if (samples.isEmpty()) return false
        if (!pinned) return true
        return evictableBeforePin > 0
    }

    private fun evictFront() {
        val s = samples.removeFirst()
        used -= s.size
        if (pinned) evictableBeforePin--
    }

    @Synchronized
    fun add(src: ByteBuffer, ptsUs: Long, key: Boolean) {
        val size = src.remaining()
        if (size <= 0 || size > cap / 4) return
        if (dropping && !key) return
        dropping = false

        while (cap - used < size) {
            if (!canEvictFront()) {
                dropping = true
                onNeedKeyframe?.invoke()
                return
            }
            evictFront()
        }

        val first = minOf(size, cap - head)
        val d = data.duplicate()
        val s = src.duplicate()
        d.position(head)
        s.limit(s.position() + first)
        d.put(s)
        if (first < size) {
            val s2 = src.duplicate()
            s2.position(src.position() + first)
            d.position(0)
            d.put(s2)
        }
        samples.addLast(Sample(head, size, ptsUs, key))
        head = (head + size) % cap
        used += size

        // Keep only the requested window (plus slack so a keyframe is always available).
        while (samples.size > 1 && ptsUs - samples.first().ptsUs > windowUs && canEvictFront()) {
            evictFront()
        }
    }

    /**
     * Pins and returns the samples from the first keyframe at or after [fromUs].
     * Call [unpin] when done reading. Returns an empty list if nothing usable.
     */
    @Synchronized
    fun pinFrom(fromUs: Long, requireKey: Boolean): List<Sample> {
        var start = -1
        for ((i, s) in samples.withIndex()) {
            if (s.ptsUs >= fromUs && (!requireKey || s.key)) { start = i; break }
        }
        if (start < 0) {
            // Window older than what we hold: fall back to the oldest keyframe we have.
            start = samples.indexOfFirst { !requireKey || it.key }
            if (start < 0) return emptyList()
        }
        pinned = true
        evictableBeforePin = start
        return samples.toList().subList(start, samples.size)
    }

    @Synchronized
    fun unpin() {
        pinned = false
        evictableBeforePin = 0
    }

    @Synchronized
    fun newestPtsUs(): Long = samples.lastOrNull()?.ptsUs ?: 0L

    /** Copies a pinned sample into [dst] (which is cleared first). Safe without the lock. */
    fun read(s: Sample, dst: ByteBuffer) {
        dst.clear()
        val first = minOf(s.size, cap - s.offset)
        val a = data.duplicate()
        a.position(s.offset)
        a.limit(s.offset + first)
        dst.put(a)
        if (first < s.size) {
            val b = data.duplicate()
            b.position(0)
            b.limit(s.size - first)
            dst.put(b)
        }
        dst.flip()
    }
}
