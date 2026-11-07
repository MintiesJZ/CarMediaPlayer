package com.saicmotor.media.service

import androidx.media3.common.C
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A song as the look-ahead downloads it, held as it comes in, so the player
 * can take it from here if the song comes up before it's all downloaded (see
 * MediaCache's Follow): the cache can't be read past what's on disk until the
 * download's done.
 *
 * The cache saves the download in pieces of [pieceBytes] (counted from where
 * each request starts) as each one fills, so only the piece being written and
 * the one before it are held: the player reads anything earlier from the
 * cache.  (A long song isn't held in memory whole.)  The look-ahead fetches
 * from where the song's copy on disk runs out, usually in one request from
 * the start; each request is a [Run].
 */
internal class LiveDownload(private val pieceBytes: Long, private val blockBytes: Int = 64 * 1024) {

    private inner class Run(val start: Long) {
        val blocks = ArrayDeque<ByteArray>()
        var dropped = 0          // blocks let go from the front (on disk by now)
        var size = 0L            // bytes received
        val end get() = start + size
        val heldFrom get() = start + dropped.toLong() * blockBytes
    }

    private val lock = Object()
    private val runs = ArrayList<Run>()
    private var stopped = false
    private var complete = false

    /** A request to the server is about to bring the song from [position]. */
    fun starting(position: Long) = synchronized(lock) {
        if (runs.lastOrNull()?.end != position) runs += Run(position)
    }

    fun received(position: Long, data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        var run = runs.lastOrNull()
        if (run == null || run.end != position) run = Run(position).also { runs += it }
        var done = 0
        while (done < length) {
            val at = (run.size % blockBytes).toInt()
            if (at == 0) run.blocks.addLast(ByteArray(blockBytes))
            val n = minOf(blockBytes - at, length - done)
            System.arraycopy(data, offset + done, run.blocks.last(), at, n)
            run.size += n
            done += n
        }
        // Pieces before the one being written and the one before it are on disk.
        val onDisk = run.start + (run.size / pieceBytes - 1).coerceAtLeast(0) * pieceBytes
        while (run.blocks.size > 1 && run.heldFrom + blockBytes <= onDisk) {
            run.blocks.removeFirst()
            run.dropped++
        }
        lock.notifyAll()
    }

    /** The download's over: [complete] if it got to the song's end. */
    fun stopped(complete: Boolean) = synchronized(lock) {
        stopped = true
        this.complete = complete
        lock.notifyAll()
    }

    /** The bytes from [position] are here, or on their way. */
    fun covers(position: Long): Boolean = synchronized(lock) {
        if (runs.any { position >= it.heldFrom && position < it.end }) return true
        if (stopped) return false
        val last = runs.lastOrNull() ?: return true   // just starting: from the first part not on disk
        position >= last.end
    }

    /**
     * Up to [length] bytes from [position], waiting for them to come in;
     * [C.RESULT_END_OF_INPUT] at the song's end.  Throws [DownloadStopped]
     * if the bytes aren't to be had from here: the download stopped before
     * getting there, or they're on disk by now (the player then reads on
     * from the cache, or the server).
     */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) { while (true) {
            val run = runs.lastOrNull { position >= it.start && position < it.end }
            if (run != null) {
                if (position < run.heldFrom) throw DownloadStopped()
                val index = ((position - run.start) / blockBytes).toInt() - run.dropped
                val at = ((position - run.start) % blockBytes).toInt()
                val n = minOf(length.toLong(), (blockBytes - at).toLong(), run.end - position).toInt()
                System.arraycopy(run.blocks[index], at, target, offset, n)
                return n
            }
            val last = runs.lastOrNull()
            if (stopped) {
                if (complete && last != null && position == last.end) return C.RESULT_END_OF_INPUT
                throw DownloadStopped()
            }
            // Before what this request's bringing: on disk, or to be fetched again.
            if (last != null && position < last.start) throw DownloadStopped()
            try {
                lock.wait(1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("stopped waiting for the download")
            }
        } }
    }
}

/**
 * The player can't take the next part of a song from the look-ahead's
 * download (it stopped first, or that part's on disk by now): it reads on
 * from the cache or the server, straight away (see WaitForNetworkPolicy).
 */
class DownloadStopped : IOException("the next part isn't in the download it was taking the song from")
