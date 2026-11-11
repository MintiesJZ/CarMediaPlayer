package shots

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.MediaFormat
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaCache
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Songs really playing, from a made-up Navidrome (see [FakeNavidrome]) that
 * misbehaves the way a car's connection does.  A stand-in Opus decoder notes
 * every packet it's given, so the tests can tell exactly what was heard.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class StreamingTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var server: FakeNavidrome
    /** (song number, packet number) of everything the decoder was given, in order. */
    private val heard = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Before
    fun setUp() {
        freshCache()
        server = FakeNavidrome()
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "secret"))
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, "audio/opus") }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("fake.opus").setIsEncoder(false)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(format).setIsEncoder(false).build())
                .build())
        // Media3 remembers the decoders it found: earlier tests looked before this one was there.
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        ShadowMediaCodec.addDecoder("fake.opus", ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024) { input, output ->
            OggOpus.label(input)?.let { heard += it }
            input.position(input.limit())
            output.put(ByteArray(3840))   // 20 ms of 48 kHz stereo
        })
    }

    @After
    fun tearDown() = server.close()

    // ── helpers ──────────────────────────────────────────────────────────

    private fun MediaService.player(): ExoPlayer =
        MediaService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(this) as ExoPlayer

    /** A new song cache, as a new process opens it (the app keeps one for as long as it runs). */
    private fun freshCache() {
        val field = MediaCache::class.java.getDeclaredField("cache").apply { isAccessible = true }
        (field.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        field.set(null, null)
    }

    /** The service, started by the launcher as the car comes on, playing the queue last saved. */
    private fun startService(): MediaService {
        val s = Robolectric.buildService(MediaService::class.java).create().get()
        looper.idle()
        s.onStartCommand(Intent().setComponent(ComponentName(app, MediaService::class.java))
            .putExtra(CommandParcelable.COMMAND_KEY,
                CommandParcelable(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY, "com.saicmotor.launcher", 0, -1)), 0, 1)
        return s
    }

    /** The server's songs as an album queue, saved as the app saves it, then played from the first. */
    private fun startPlaying(): MediaService {
        val store = ResumeStore(app)
        val items = server.songs.values.map {
            ResumeStore.SavedItem("sub:${it.id}", it.title, "Artist", "Album", null, "al1", "ar1", it.seconds * 1_000L)
        }
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId("al1"))
        store.saveItem(items.first(), 0L)
        store.saveSnapshot(items)
        return startService()
    }

    private fun packets(song: Int) = server.songs.values.elementAt(song).seconds * 1000 / OggOpus.PACKET_MS

    /** Song [song] played right to its end. */
    private fun heardAll(song: Int) = heard.any { it.first == song && it.second == packets(song) - 1 }

    /**
     * Lets the car run for up to [ms] on its clock (stopping early once [until]),
     * noting which song was playing when; [during] gets the time so far.
     */
    private fun drive(s: MediaService, ms: Long, until: () -> Boolean = { false },
                      during: (Long) -> Unit = {}): List<Pair<Long, String?>> {
        val start = SystemClock.elapsedRealtime()
        val songs = ArrayList<Pair<Long, String?>>()
        while (SystemClock.elapsedRealtime() - start < ms && !until()) {
            looper.idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
            val t = SystemClock.elapsedRealtime() - start
            during(t)
            val id = s.player().currentMediaItem?.mediaId
            if (songs.lastOrNull()?.second != id) songs += t to id
        }
        return songs
    }

    /** What the decoder got of [song], in order: every packet once, from the first, is the whole song. */
    private fun assertHeardWhole(song: Int) =
        assertEquals("song $song heard once, start to end, nothing missed or repeated",
            (0 until packets(song)).toList(), heard.filter { it.first == song }.map { it.second })

    private fun streamRequests(song: Int) = server.streamRequests(server.songs.keys.elementAt(song))

    /** The song as it is in the song cache. */
    private fun kept(song: Int): ByteArray {
        val mediaId = "sub:" + server.songs.keys.elementAt(song)
        val length = MediaCache.cachedBytesFromStart(app, mediaId)
        if (length == 0L) return ByteArray(0)
        val source = MediaCache.dataSourceFactory(app).createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.parse("http://127.0.0.1:9/")).setKey(MediaCache.keyFor(mediaId))
            .setLength(length).build())
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    /** The whole song is in the cache, the same as the server's (but for the serial number each transcode gets). */
    private fun assertKeptWhole(song: Int) {
        val original = server.songs.values.elementAt(song).opus
        assertEquals("song $song kept whole", original.size, kept(song).size)
        assertArrayEquals("song $song kept as sent", OggOpus.withSerial(original, 0), OggOpus.withSerial(kept(song), 0))
        assertEquals(1.0f, MediaCache.cachedFraction(app, "sub:" + server.songs.keys.elementAt(song)))
    }

    /**
     * What's in the song cache for [song] is the song (as far as it goes): a
     * piece the player and the look-ahead both wanted at once may be missing,
     * but nothing in it is anything else.
     */
    private fun assertKeptIsTheSong(song: Int) {
        val original = server.songs.values.elementAt(song).opus
        val kept = kept(song)
        assertTrue("song $song: ${kept.size} bytes kept of ${original.size}", kept.size <= original.size)
        val serialAndChecksum = HashSet<Int>()
        var i = 0
        while (i + 27 <= original.size) {
            for (k in 14..17) serialAndChecksum += i + k
            for (k in 22..25) serialAndChecksum += i + k
            val segments = original[i + 26].toInt() and 0xFF
            i += 27 + segments + (0 until segments).sumOf { original[i + 27 + it].toInt() and 0xFF }
        }
        for (k in kept.indices) if (k !in serialAndChecksum && kept[k] != original[k]) {
            throw AssertionError("song $song: what's kept differs from the song at byte $k")
        }
    }

    // ── tests ────────────────────────────────────────────────────────────

    /**
     * Every song is kept as it plays, whether the look-ahead downloaded it or
     * the player streamed it, and none is downloaded twice.  (The player's
     * songs weren't kept — ExoPlayer doesn't keep a stream of unknown length
     * — so the look-ahead then downloaded each next song a second time.  And
     * where the two overlapped, both fetched the song and a piece went
     * unkept.)
     */
    @Test
    fun everySongIsKeptAndNoneDownloadedTwice() {
        server.addSongs(3, seconds = 10)
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(2) })
        for (song in 0..2) {
            assertHeardWhole(song)
            assertKeptWhole(song)
            assertEquals("song $song fetched once: ${streamRequests(song).map { it.range }}", 1, streamRequests(song).size)
        }
        s.onDestroy()
    }

    /**
     * The connection drops part-way through a song still streaming (Wi-Fi to
     * mobile data leaving home): it carries on from where it was, not from
     * the start, and the song is still kept whole.
     */
    @Test
    fun aDroppedConnectionCarriesOnWhereItWas() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt == 1) FakeNavidrome.Reply.CutAfter(100_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) })
        assertHeardWhole(0)
        assertHeardWhole(1)
        // Part-way, not from the start: how far depends on how much the player
        // had read when the connection was reset (what hadn't been is lost).
        val again = streamRequests(0)[1]
        assertTrue("asked to carry on from part-way: ${again.range}", again.range!!.removePrefix("bytes=").substringBefore('-').toInt() in 1..100_000)
        assertKeptWhole(0)
        s.onDestroy()
    }

    /**
     * The next song comes up (a skip, or a slow signal) while the look-ahead
     * is still downloading it: the player takes it from that download, so
     * it's fetched once and kept whole.  (The player used to fetch it again
     * alongside; a piece read while the look-ahead still held it went unkept,
     * and on a slow signal the two shared the connection.)
     */
    @Test
    fun aSongStillDownloadingWhenSkippedToIsFetchedOnceAndKeptWhole() {
        // A long first song: the player doesn't get on to the next one itself
        // for a while (it loads up to 120 s ahead).  The next is long enough
        // that the download doesn't hold all of it (what's on disk is read
        // from there).
        server.addSongs(1, seconds = 300)
        server.addSongs(1, seconds = 100)
        server.addSongs(1, seconds = 20)
        // The look-ahead's download of song 1 gets going, then stalls for a moment.
        server.plan = { song, attempt -> if (song == 1 && attempt == 1) FakeNavidrome.Reply.StallAfter(120_000, 3_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 30_000, until = { streamRequests(1).firstOrNull()?.sent?.get() ?: 0 >= 120_000 })
        assertEquals("the look-ahead's downloading it", 1, streamRequests(1).size)
        s.player().seekToNextMediaItem()   // skipped to while it's downloading
        drive(s, 60_000, until = { heardAll(1) })
        println("song 1 requests: ${streamRequests(1).map { "${it.range} ${it.sent.get()}" }}")
        assertHeardWhole(1)
        assertKeptWhole(1)
        assertEquals("fetched once", 1, streamRequests(1).size)
        s.onDestroy()
    }

    /**
     * The connection drops while the player's taking the song from the
     * look-ahead's download: once the server's back, the player fetches the
     * rest itself, and the song's still heard and kept whole.
     */
    @Test
    fun aDownloadCutOffWhileItsSongPlaysIsCarriedOn() {
        server.addSongs(1, seconds = 300)
        server.addSongs(2, seconds = 20)
        server.plan = { song, attempt -> if (song == 1 && attempt == 1) FakeNavidrome.Reply.StallAfter(120_000, 3_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 30_000, until = { streamRequests(1).firstOrNull()?.sent?.get() ?: 0 >= 120_000 })
        s.player().seekToNextMediaItem()
        drive(s, 90_000, until = { heardAll(1) }, during = { t ->
            if (t in 2_000 until 2_020) server.goDown()      // a dead spot, as the download stalls
            if (t in 12_000 until 12_020) server.comeBack()
        })
        println("song 1 requests: ${streamRequests(1).map { "${it.range} ${it.sent.get()}" }}")
        assertHeardWhole(1)
        assertKeptWhole(1)
        val again = streamRequests(1).last()
        assertTrue("carried on from part-way: ${again.range}", (again.range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0) >= 100_000)
        s.onDestroy()
    }

    /**
     * A weak signal (about twice what the music needs): the songs play
     * through their changes without stopping to load.
     */
    @Test
    fun aWeakSignalPlaysThroughSongChanges() {
        server.addSongs(4, seconds = 20)
        server.bytesPerSecond = 200_000
        val s = startPlaying()
        var started = false
        val stalls = ArrayList<String>()
        drive(s, 240_000, until = { heardAll(3) }, during = { t ->
            val p = s.player()
            if (p.isPlaying) started = true
            if (started && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
                stalls += "$t ms: song ${p.currentMediaItemIndex} at ${p.currentPosition} ms"
        })
        println("stalls (${stalls.size} ticks): ${stalls.take(20)}")
        for (song in 0..3) assertHeardWhole(song)
        assertTrue("stopped to load ${stalls.size * 20} ms: ${stalls.take(5)}", stalls.size * 20 <= 200)
        s.onDestroy()
    }

    /** No server for a while mid-song (a dead spot): the song waits, then carries on. */
    @Test
    fun aDeadSpotIsWaitedOut() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt == 1) FakeNavidrome.Reply.StallAfter(60_000, 3_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) }, during = { t ->
            if (t in 1_000 until 1_020) server.goDown()
            if (t in 11_000 until 11_020) server.comeBack()
        })
        assertHeardWhole(0)
        assertHeardWhole(1)
        assertKeptWhole(0)
        s.onDestroy()
    }

    /**
     * Navidrome sends an error message instead of the song (as Subsonic
     * errors come: HTTP 200, JSON).  It's never kept as the song: the song
     * plays once the server sends it.  (It used to be kept, and the song then
     * failed every time, after half a minute of silence.)
     */
    @Test
    fun anErrorMessageIsNeverKeptAsTheSong() {
        server.addSongs(3, seconds = 10)
        server.plan = { song, attempt -> if (song == 1 && attempt == 1) FakeNavidrome.Reply.SubsonicError(0, "Error transcoding") else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(2) })
        assertHeardWhole(1)
        assertKeptIsTheSong(1)
        s.onDestroy()
    }

    /** A song the server doesn't have (a queue saved before the library changed) is skipped straight away. */
    @Test
    fun aSongTheServerDoesntHaveIsSkippedStraightAway() {
        server.addSongs(3, seconds = 10)
        server.plan = { song, _ -> if (song == 1) FakeNavidrome.Reply.SubsonicError(70, "Song not found") else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        val songs = drive(s, 60_000, until = { heardAll(2) })
        val ids = server.songs.keys.map { "sub:$it" }
        // Song 0 ends at 10 s; song 2 follows it straight away, not after
        // half a minute of trying song 1.
        val reached2 = songs.first { it.second == ids[2] }.first
        assertTrue("song 2 at $reached2 ms", reached2 < 13_000)
        assertHeardWhole(0)
        assertHeardWhole(2)
        assertTrue("not asked for again and again: ${streamRequests(1).size}", streamRequests(1).size <= 2)
        assertEquals("nothing kept for it", 0L, MediaCache.cachedBytesFromStart(app, ids[1]))
        s.onDestroy()
    }

    /**
     * The same, when the error comes for a later song (loaded ahead) while
     * one plays: that one plays on, and the missing song leaves the queue.
     */
    @Test
    fun aLaterSongTheServerDoesntHaveLeavesTheQueue() {
        server.addSongs(3, seconds = 10)
        val s = startPlaying()
        drive(s, 1_000)
        val ids = server.songs.keys.map { "sub:$it" }
        val error = androidx.media3.exoplayer.ExoPlaybackException.createForSource(
            com.saicmotor.media.service.SongRefused(server.songs.keys.elementAt(2), 70, "error 70: Song not found"),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        MediaService::class.java.getDeclaredMethod("recoverFromError", androidx.media3.common.PlaybackException::class.java)
            .apply { isAccessible = true }.invoke(s, error)
        val p = s.player()
        assertEquals(listOf(ids[0], ids[1]), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId })
        assertEquals("song 0 plays on", ids[0], p.currentMediaItem?.mediaId)
        s.onDestroy()
    }

    /**
     * The server refuses the login (the password was changed on it): the car
     * says so and stays on the song — not skipping through the queue — and
     * plays it once the new password is put in Settings.
     */
    @Test
    fun aRefusedLoginIsShownAndPlaysOnceFixed() {
        server.addSongs(2, seconds = 10)
        server.password = "changed"
        val s = startPlaying()
        val first = "sub:" + server.songs.keys.first()
        val songs = drive(s, 20_000)
        assertEquals("stays on the song", listOf(first), songs.mapNotNull { it.second }.distinct())
        assertTrue("nothing plays", heard.isEmpty())
        val session = MediaService::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(s) as android.support.v4.media.session.MediaSessionCompat
        assertEquals(app.getString(com.saicmotor.media.R.string.error_auth_title),
            session.controller.playbackState.errorMessage?.toString())
        // Fixed in Settings (as the settings screen saves it).
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "changed"))
        com.saicmotor.media.subsonic.SubsonicClient.invalidateCache()
        drive(s, 30_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /** Navidrome at its limit of transcodes ("429, try again in 5 s"): the song waits, then plays; nothing's skipped. */
    @Test
    fun aBusyServerIsWaitedFor() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt <= 2) FakeNavidrome.Reply.Busy else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) })
        assertHeardWhole(0)
        assertHeardWhole(1)
        assertEquals(3, streamRequests(0).size)
        s.onDestroy()
    }

    /** No server when the car starts: the song starts as soon as it's back. */
    @Test
    fun startsAsSoonAsTheServerIsBack() {
        server.addSongs(1, seconds = 10)
        server.goDown()
        val s = startPlaying()
        val start = SystemClock.elapsedRealtime()
        drive(s, 30_000, until = { heard.isNotEmpty() }, during = { t -> if (t in 8_000 until 8_020) server.comeBack() })
        val startedAt = SystemClock.elapsedRealtime() - start
        assertTrue("started ${startedAt - 8_000} ms after the server was back", startedAt in 8_000..11_000)
        drive(s, 30_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /**
     * The car's switched off part-way through a song that streamed (not
     * downloaded ahead), and starts again with no signal: the song carries on
     * from the song cache.
     */
    @Test
    fun theSongPlayingIsKeptForWhenTheCarStartsAgain() {
        server.addSongs(2, seconds = 30)
        val first = startPlaying()
        drive(first, 3_000)
        first.player().pause()
        drive(first, 500)
        val at = ResumeStore(app).load()!!.positionMs   // where the app noted it was
        assertTrue("part-way through song 0 ($at ms)", at > 1_000)
        first.onDestroy()
        freshCache()
        server.goDown()
        heard.clear()
        val s = startService()
        drive(s, 20_000, until = { heardAll(0) })
        val song0 = heard.filter { it.first == 0 }.map { it.second }
        assertTrue("played without the server", song0.isNotEmpty())
        assertTrue("from where it was (${at} ms), not the start: packet ${song0.first()}", song0.first() * OggOpus.PACKET_MS >= at - 1_000)
        assertEquals("to the end", packets(0) - 1, song0.last())
        s.onDestroy()
    }

    // ── other sounds, pausing, and what's reported ───────────────────────

    private val audio get() = shadowOf(app.getSystemService(android.media.AudioManager::class.java))

    /** What the car tells the app about the speakers (AudioManager.AUDIOFOCUS_…). */
    private fun MediaService.speakers(change: Int) =
        (MediaService::class.java.getDeclaredField("mediaFocusListener").apply { isAccessible = true }.get(this)
            as android.media.AudioManager.OnAudioFocusChangeListener).onAudioFocusChange(change)

    /** The play / pause button of the widgets and the notification. */
    private fun MediaService.playPauseButton() {
        onStartCommand(Intent(app, MediaService::class.java).setAction(MediaService.ACTION_PLAY_PAUSE), 0, 2)
    }

    /** One song, still loading: its first part has come, then the connection stalls for a moment. */
    private fun startLoading(): MediaService {
        server.addSongs(1, seconds = 30)
        server.plan = { _, attempt -> if (attempt == 1) FakeNavidrome.Reply.StallAfter(20_000, 2_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 20_000, until = { (streamRequests(0).firstOrNull()?.sent?.get() ?: 0) >= 20_000 })
        drive(s, 500)
        assertTrue("still loading", s.player().playWhenReady && !s.player().isPlaying)
        return s
    }

    /**
     * The radio (or YouTube) takes the speakers while a song is still
     * loading: the music stays off.  (It wasn't "playing" yet, so it wasn't
     * paused: it started once it had loaded, and took the speakers back.)
     */
    @Test
    fun aSongStillLoadingStaysOffWhenTheRadioTakesOver() {
        val s = startLoading()
        val asked = audio.lastAudioFocusRequest
        s.speakers(android.media.AudioManager.AUDIOFOCUS_LOSS)
        drive(s, 40_000, until = { heardAll(0) })
        assertTrue("paused", !s.player().playWhenReady)
        assertTrue("not played over the radio", !heardAll(0))
        assertTrue("the speakers weren't taken back", audio.lastAudioFocusRequest === asked)
        s.onDestroy()
    }

    /** A call (or a navigation prompt) while a song is still loading: it waits for the call to end. */
    @Test
    fun aSongStillLoadingWaitsForACallToEnd() {
        val s = startLoading()
        s.speakers(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        drive(s, 40_000, until = { heardAll(0) })
        assertTrue("held during the call", !s.player().playWhenReady && !heardAll(0))
        s.speakers(android.media.AudioManager.AUDIOFOCUS_GAIN)
        drive(s, 40_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /**
     * Play pressed during a call: the car is asked for the speakers again,
     * and says no, so the music stays off until the call ends.  (It wasn't
     * asked: the music played over the call.)
     */
    @Test
    fun playPressedDuringACallWaitsForTheCallToEnd() {
        server.addSongs(1, seconds = 30)
        val s = startPlaying()
        drive(s, 20_000, until = { s.player().isPlaying })
        s.speakers(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        drive(s, 500)
        assertTrue("paused for the call", !s.player().playWhenReady)
        val asked = audio.lastAudioFocusRequest
        audio.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        s.playPauseButton()
        drive(s, 500)
        assertTrue("asked for the speakers again", audio.lastAudioFocusRequest !== asked)
        assertTrue("refused: still paused", !s.player().playWhenReady)
        audio.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        s.speakers(android.media.AudioManager.AUDIOFOCUS_GAIN)
        drive(s, 500)
        assertTrue("carries on after the call", s.player().playWhenReady)
        s.onDestroy()
    }

    /** The play / pause button while a song is still loading pauses it (it did nothing: the song wasn't "playing" yet). */
    @Test
    fun thePauseButtonPausesASongStillLoading() {
        val s = startLoading()
        s.playPauseButton()
        drive(s, 500)
        assertTrue("paused", !s.player().playWhenReady)
        drive(s, 30_000, until = { heardAll(0) })
        assertTrue("stays paused once it's loaded", !heardAll(0))
        s.playPauseButton()
        drive(s, 40_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /**
     * Paused by the driver, then the song can't be loaded: trying again
     * leaves it paused.  (Trying again also pressed play, so the music
     * started by itself once the server was fine.)
     */
    @Test
    fun pausedMusicStaysPausedWhenTheSongFailsToLoad() {
        server.addSongs(1, seconds = 60)
        var broken = true
        server.plan = { _, attempt -> when {
            attempt == 1 -> FakeNavidrome.Reply.CutAfter(150_000)
            broken       -> FakeNavidrome.Reply.Status(404)
            else         -> FakeNavidrome.Reply.Whole } }
        val s = startPlaying()
        drive(s, 20_000, until = { s.player().isPlaying })
        s.playPauseButton()
        drive(s, 300)
        assertTrue("paused", !s.player().playWhenReady)
        drive(s, 25_000, until = { s.player().playerError != null })
        broken = false
        drive(s, 40_000, until = { s.player().playWhenReady })
        assertTrue("still paused, with the server fine again", !s.player().playWhenReady)
        s.playPauseButton()
        drive(s, 60_000, until = { heardAll(0) })
        assertTrue("plays when asked", heardAll(0))
        s.onDestroy()
    }

    /**
     * The next songs can't be fetched for a while (a dead spot) as the one
     * playing carries on from what's in hand: they're tried until they come.
     * (Each was given up on after five tries, until the app restarted: the
     * songs straight after a dead spot were never downloaded ahead.)
     */
    @Test
    fun theNextSongsAreDownloadedOnceADeadSpotEnds() {
        server.addSongs(1, seconds = 1_800)   // long: the player doesn't get to the next one itself meanwhile
        server.addSongs(2, seconds = 20)
        var blocked = true
        server.plan = { song, _ -> if (song >= 1 && blocked) FakeNavidrome.Reply.CutAfter(0) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 120_000, until = { streamRequests(1).size >= 8 })
        assertTrue("tried more than five times: ${streamRequests(1).size}", streamRequests(1).size >= 8)
        blocked = false
        fun kept(song: Int) = MediaCache.isCached(app, "sub:" + server.songs.keys.elementAt(song))
        drive(s, 40_000, until = { kept(1) && kept(2) })
        assertEquals("still on the long song", 0, s.player().currentMediaItemIndex)
        assertKeptWhole(1)
        assertKeptWhole(2)
        s.onDestroy()
    }

    /**
     * The car starts again three quarters through a song: the play isn't
     * reported to Navidrome a second time.  (It was, at every start past
     * half-way: one listen counted twice.)
     */
    @Test
    fun aPlayIsntReportedAgainWhenTheCarStartsPartWayThrough() {
        server.addSongs(1, seconds = 900)
        val first = startPlaying()
        drive(first, 200_000, until = { heardAll(0) })
        first.onDestroy()
        freshCache(); heard.clear()
        val item = server.songs.values.first().let {
            ResumeStore.SavedItem("sub:${it.id}", it.title, "Artist", "Album", null, "al1", "ar1", it.seconds * 1_000L)
        }
        ResumeStore(app).saveItem(item, 700_000L)
        val reported = server.scrobbles(true).size
        val s = startService()
        var playingAtATick = false
        drive(s, 14_000, during = { t -> if (t in 5_000..11_000 && s.player().isPlaying) playingAtATick = true })
        assertTrue("carried on part-way: ${heard.firstOrNull()}", (heard.firstOrNull()?.second ?: 0) >= 34_000)
        assertTrue("was playing when plays are looked at", playingAtATick)
        assertEquals("not reported again", reported, server.scrobbles(true).size)
        s.onDestroy()
    }

    /**
     * A song chosen and left in its first minutes, long enough that it used
     * to be only part downloaded by then: it's all on disk, so it carries on
     * from where it was when the car starts again (with no signal, here).
     * (Only part there, it can't be picked up part-way: it started over.)
     */
    @Test
    fun aLongSongLeftEarlyCarriesOnWhereItWas() {
        server.addSongs(1, seconds = 400)
        val first = startPlaying()
        drive(first, 60_000, until = { heard.size >= 400 })
        first.player().pause()
        drive(first, 3_000)
        val at = ResumeStore(app).load()!!.positionMs
        first.onDestroy()
        freshCache()
        assertTrue("all of it is on disk", MediaCache.isCached(app, "sub:" + server.songs.keys.first()))
        freshCache()
        server.goDown()
        heard.clear()
        val s = startService()
        drive(s, 120_000, until = { heardAll(0) })
        val song0 = heard.filter { it.first == 0 }.map { it.second }
        assertTrue("played", song0.isNotEmpty())
        assertTrue("from where it was ($at ms), not the start: packet ${song0.first()}",
            at > 2_000 && song0.first() * OggOpus.PACKET_MS >= at - 1_000)
        assertEquals("to the end", packets(0) - 1, song0.last())
        s.onDestroy()
    }

    /**
     * The server ends a song early without an error (a transcode that died):
     * the next time, the rest is asked for and the whole song plays.  (What
     * came was kept as the whole song, which then stopped short every time.)
     */
    @Test
    fun aSongTheServerCutShortIsWholeNextTime() {
        server.addSongs(2, seconds = 20)
        server.plan = { song, attempt -> if (song == 0 && attempt == 1) FakeNavidrome.Reply.EndAfter(100_000) else FakeNavidrome.Reply.Whole }
        val first = startPlaying()
        drive(first, 60_000, until = { heardAll(1) })
        assertTrue("cut short the first time", !heardAll(0))
        first.onDestroy()
        freshCache(); heard.clear()
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) })
        assertHeardWhole(0)
        assertKeptWhole(0)
        assertEquals("only the rest was asked for", "bytes=100000-", streamRequests(0).last().range)
        s.onDestroy()
    }

    /**
     * No server for a long time (a long drive with no coverage, the songs
     * in hand all played): the music comes back when the server does.  (After
     * a quarter of an hour of errors it gave up for good.)
     */
    @Test
    fun aLongOutageIsWaitedOutHoweverLong() {
        server.addSongs(1, seconds = 20)
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val online = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(online).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, online)   // a signal, but no server
        server.goDown()
        val s = startPlaying()
        val errors = ArrayList<Long>()
        s.player().addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { errors += SystemClock.elapsedRealtime() }
        })
        // The car's clock in big steps: an hour and more of trying.
        fun wait(minutes: Int, until: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + minutes * 60_000L
            while (SystemClock.elapsedRealtime() < end && !until()) { looper.idleFor(Duration.ofMillis(500)); Thread.sleep(2) }
        }
        wait(180) { errors.size >= 3 && errors.last() - errors.first() > 16 * 60_000L }
        assertTrue("errors over more than a quarter of an hour: ${errors.map { (it - errors.first()) / 60_000 }} min",
            errors.size >= 3 && errors.last() - errors.first() > 16 * 60_000L)
        wait(1) { false }
        server.comeBack()
        wait(30) { heardAll(0) }
        assertHeardWhole(0)
        s.onDestroy()
    }

    /** …but a song that comes up in the ordinary way is still to be reported, from its start. */
    @Test
    fun aSongThatComesUpIsStillToBeReported() {
        server.addSongs(2, seconds = 20)
        val s = startPlaying()
        val reported = MediaService::class.java.getDeclaredField("playReported").apply { isAccessible = true }
        val atEachSong = ArrayList<String>()
        s.player().addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                atEachSong += "song ${s.player().currentMediaItemIndex}: reported=${reported.getBoolean(s)}"
            }
        })
        drive(s, 60_000, until = { heardAll(1) })
        assertEquals("song 1: reported=false", atEachSong.last())
        s.onDestroy()
    }
}
