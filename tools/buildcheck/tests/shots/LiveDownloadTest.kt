package shots

import androidx.media3.common.C
import com.saicmotor.media.service.DownloadStopped
import com.saicmotor.media.service.LiveDownload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The look-ahead's download as the player takes a song from it (no Android
 * needed): small pieces and blocks here, so what's let go shows.
 */
class LiveDownloadTest {

    private val song = Random(7).nextBytes(10_000)

    private fun LiveDownload.receive(from: Int, to: Int, step: Int = 250) {
        var at = from
        while (at < to) { val n = minOf(step, to - at); received(at.toLong(), song, at, n); at += n }
    }

    private fun LiveDownload.readAll(from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(333)
        var at = from.toLong()
        while (true) {
            val n = read(at, buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, n)
            at += n
        }
    }

    private fun stops(block: () -> Unit) =
        try { block(); false } catch (_: DownloadStopped) { true }

    /** Only the piece being saved and the one before it are held: anything earlier is read from the cache. */
    @Test
    fun onlyWhatMayNotBeOnDiskIsHeld() {
        val d = LiveDownload(pieceBytes = 1_000, blockBytes = 100)
        d.starting(0)
        d.receive(0, 2_500)
        assertFalse("on disk by now", d.covers(999))
        assertTrue(stops { d.read(500, ByteArray(10), 0, 10) })
        assertTrue(d.covers(1_000))
        assertTrue("still coming", d.covers(2_600))
        val got = ByteArray(100)
        assertEquals(100, d.read(1_000, got, 0, 100))
        assertArrayEquals(song.copyOfRange(1_000, 1_100), got)
    }

    /** A reader keeps up with the download, waiting for each part, and gets the whole song and then its end. */
    @Test
    fun aReaderFollowingTheDownloadGetsTheSongThenItsEnd() {
        val d = LiveDownload(pieceBytes = 1_000_000)
        d.starting(0)
        var got: ByteArray? = null
        val reader = thread { got = d.readAll(0) }
        for (at in 0 until song.size step 1_000) { d.receive(at, at + 1_000); Thread.sleep(2) }
        d.stopped(complete = true)
        reader.join(5_000)
        assertArrayEquals(song, got)
    }

    /** A download that stops part-way: the reader gets what came, then is told to read on elsewhere. */
    @Test
    fun aDownloadThatStopsSendsTheReaderElsewhere() {
        val d = LiveDownload(pieceBytes = 1_000_000)
        d.starting(4_000)                     // the first 4000 bytes were on disk already
        d.receive(4_000, 6_000)
        d.stopped(complete = false)
        assertFalse(d.covers(0))
        assertEquals(10, d.read(5_000, ByteArray(10), 0, 10))
        assertTrue(stops { d.read(6_000, ByteArray(10), 0, 10) })
    }
}
