package com.saicmotor.media.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.saicmotor.media.data.Storage
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A disk cache of songs, shared by the player and the pre-cacher.
 *
 * Songs are cached under their Subsonic id, not their URL: the URL carries a
 * login token that's different every time, so caching by URL would never hit.
 *
 * Upcoming songs are downloaded ahead of time ([precache]), so the next song
 * (or a skip) starts straight away even on a slow mobile connection, and the
 * song after a tunnel is already there.  Old songs are dropped once the cache
 * is full.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val MAX_BYTES  = 400L * 1024 * 1024
    /**
     * Songs are written to the cache in pieces this big, each saved as it
     * fills.  Small, because the car just loses power: a piece still being
     * written is lost, and the song that was playing should be on disk, up
     * to the point that was playing, when the car starts again.
     */
    private const val PIECE_BYTES = 512L * 1024

    @Volatile private var cache: SimpleCache? = null

    /**
     * The cache key for a song (see the class comment).  "v2": 2.0.2–2.0.4
     * cached the original FLAC files by mistake (see [dataSourceFactory]);
     * a new key leaves those to be cleared out first as the cache fills.
     */
    fun keyFor(mediaId: String): String = "song-v2:$mediaId"

    /** In device-protected storage, so songs can play the moment the head unit boots (see Storage). */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                val storage = Storage.context(context.applicationContext)
                SimpleCache(
                    File(storage.cacheDir, "songs"),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(storage)
                )
            }.also { cache = it }
        }

    /**
     * Where the player reads songs from: the cache, falling back to the
     * server — or, for a song the look-ahead is still downloading, to that
     * download as it comes in (see [Follow]).
     */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val server = serverFactory(context)
        val cache = cache(context)
        return cacheFactory(context) {
            Follow(server.createDataSource()) { key -> ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
        }
    }

    private fun cacheFactory(context: Context, upstream: DataSource.Factory): CacheDataSource.Factory {
        val cache = cache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(PIECE_BYTES))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** The songs from the server. */
    private fun serverFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return ResolvingDataSource.Factory(
                // An error message in place of the song is never saved as the song (see SongReplies).
                SongReplies.Factory(
                    // No User-Agent of our own: Navidrome treats each client name +
                    // User-Agent as a separate "player", and its transcoding
                    // (e.g. Opus 128k for the car) is set per player.  Songs must
                    // be fetched with the same User-Agent as the app's other
                    // requests (Android's default), or Navidrome registers a new
                    // player with no transcoding and sends the original files.
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        // Short: while the connection is coming up, a hung attempt
                        // shouldn't hold up the next one (see WaitForNetworkPolicy).
                        .setConnectTimeoutMs(6_000)
                        .setReadTimeoutMs(15_000)
                )
            ) { dataSpec -> dataSpec.withUri(withCurrentLogin(app, dataSpec.uri)) }
    }

    /**
     * A song's address with the server and login from Settings as they are
     * now.  The address of every song in the queue carries the login as it
     * was when the queue was made, so after the password (or the server's
     * address) was changed, the queue kept failing until something else was
     * played.
     */
    private fun withCurrentLogin(context: Context, uri: Uri): Uri {
        if (uri.path?.endsWith("/rest/stream") != true) return uri
        val id = uri.getQueryParameter("id") ?: return uri
        val cfg = SubsonicSettings.load(context).takeIf { it.isValid } ?: return uri
        return Uri.parse(SubsonicClient.streamUrl(cfg, id))
    }

    /** How much of the song is on disk (0–1), or null if it's never been fetched. */
    fun cachedFraction(context: Context, mediaId: String): Float? {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length <= 0) return null
        return c.getCachedBytes(key, 0, length).toFloat() / length
    }

    /** Bytes of the song on disk from its start (a transcoded song's full length isn't known until it's all in). */
    fun cachedBytesFromStart(context: Context, mediaId: String): Long =
        cache(context).getCachedLength(keyFor(mediaId), 0, Long.MAX_VALUE).coerceAtLeast(0)

    /** True if the whole song is already on disk. */
    fun isCached(context: Context, mediaId: String): Boolean {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /**
     * What's kept of a song turned out not to be all of it (the server ended
     * it early without saying so): the next time it's wanted, the rest is
     * asked for, where it used to stop short every time.  What's kept stays
     * (it's the song's first part).
     */
    fun notWhole(context: Context, mediaId: String) {
        try {
            cache(context).applyContentMetadataMutations(
                keyFor(mediaId), ContentMetadataMutations().remove(ContentMetadata.KEY_CONTENT_LENGTH))
        } catch (_: Exception) {
            // Only the cache: the song then stays as it is until it's dropped.
        }
    }

    /**
     * Downloads a song into the cache.  Blocking: run it off the main thread.
     * Returns a [CacheWriter] handle via [onStart] so it can be cancelled.
     * While it runs, the player can take the song from it (see [Follow]).
     */
    fun precache(context: Context, mediaId: String, uri: Uri, onStart: (CacheWriter) -> Unit) {
        val key = keyFor(mediaId)
        val download = LiveDownload(PIECE_BYTES)
        val server = serverFactory(context)
        val source = cacheFactory(context) { Recording(server.createDataSource(), download) }.createDataSource()
        val writer = CacheWriter(source, DataSpec.Builder().setUri(uri).setKey(key).build(), null, null)
        live[key] = download
        var complete = false
        try {
            onStart(writer)
            writer.cache()
            complete = true
        } finally {
            live.remove(key, download)
            download.stopped(complete)
        }
    }

    // ── The player and the look-ahead on the same song ────────────────────

    /** Songs the look-ahead is downloading, by cache key. */
    private val live = ConcurrentHashMap<String, LiveDownload>()
    /** Songs the player is fetching from the server itself, by cache key (how many connections). */
    private val playerFetching = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * True while the player is fetching the song from the server itself
     * (the next song, once the one playing is all in): the look-ahead leaves
     * it, as the cache keeps what the player fetches.  Fetching it as well
     * downloaded it twice, sharing a weak signal, and kept nothing of it.
     */
    fun playerIsFetching(mediaId: String): Boolean = (playerFetching[keyFor(mediaId)]?.get() ?: 0) > 0

    /**
     * The player's way to the server.  For a song the look-ahead is still
     * downloading (skipped to, or come up on a weak signal), the cache can't
     * be read past what's on disk until that download's done, and the player
     * used to fetch the song again alongside it: twice the data on the same
     * signal (the song stopped to load), and what it read while the download
     * held the song wasn't kept (a piece missing for next time).  It takes
     * the song from the download as it comes in instead.
     *
     * The player doesn't learn the song's length when the download that
     * finished it was someone else's, so at the end of what's on disk it
     * asks for more: past the end of a song whose length is known by then,
     * that's answered here (nothing more), not by the server (which
     * transcodes the whole song again to send nothing).
     */
    private class Follow(private val server: DataSource, private val knownLength: (String) -> Long) : DataSource {
        private var download: LiveDownload? = null
        private var pastEnd = false
        private var key: String? = null
        private var spec: DataSpec? = null
        private var position = 0L
        private var remaining = C.LENGTH_UNSET.toLong()

        override fun addTransferListener(transferListener: TransferListener) = server.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val length = dataSpec.key?.let(knownLength) ?: C.LENGTH_UNSET.toLong()
            if (length != C.LENGTH_UNSET.toLong() && dataSpec.position >= length) {
                pastEnd = true
                spec = dataSpec
                return 0
            }
            val d = dataSpec.key?.let { live[it] }
            if (d != null && d.covers(dataSpec.position)) {
                download = d
                spec = dataSpec
                position = dataSpec.position
                remaining = dataSpec.length
                return C.LENGTH_UNSET.toLong()
            }
            download = null
            key = dataSpec.key?.also { playerFetching.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
            try {
                return server.open(dataSpec)
            } catch (e: Exception) {
                done()
                throw e
            }
        }

        private fun done() {
            key?.let { k -> playerFetching[k]?.let { if (it.decrementAndGet() <= 0) playerFetching.remove(k, it) } }
            key = null
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pastEnd) return if (length == 0) 0 else C.RESULT_END_OF_INPUT
            val d = download ?: return server.read(buffer, offset, length)
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val n = d.read(position, buffer, offset, want)
            if (n == C.RESULT_END_OF_INPUT) return n
            position += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            return n
        }

        override fun getUri(): Uri? = if (download != null || pastEnd) spec?.uri else server.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (download != null || pastEnd) emptyMap() else server.responseHeaders

        override fun close() {
            if (pastEnd) { pastEnd = false; return }
            if (download != null) { download = null; return }
            done()
            server.close()
        }
    }

    /** The look-ahead's way to the server: what comes in is also handed to [download], for [Follow]. */
    private class Recording(private val server: DataSource, private val download: LiveDownload) : DataSource {
        private var position = 0L

        override fun addTransferListener(transferListener: TransferListener) = server.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            download.starting(dataSpec.position)
            val length = server.open(dataSpec)
            position = dataSpec.position
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = server.read(buffer, offset, length)
            if (n > 0) {
                download.received(position, buffer, offset, n)
                position += n
            }
            return n
        }

        override fun getUri(): Uri? = server.uri
        override fun getResponseHeaders(): Map<String, List<String>> = server.responseHeaders
        override fun close() = server.close()
    }
}
