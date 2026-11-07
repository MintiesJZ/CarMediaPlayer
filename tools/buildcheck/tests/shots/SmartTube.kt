package shots

import android.content.Context
import android.net.Uri

/**
 * SmartTube (the YouTube app used in the car) showing a video, run from its
 * own code (converted from its APK, see tools/firmware/README.md): its player
 * as its ExoPlayerInitializer makes it (ExoPlayer 2.10, which takes and gives
 * up the speakers itself) and its media session as its PlaybackFragment sets
 * it up (MediaSessionConnector), without its screens.  The "video" is a song
 * from the made-up Navidrome: only its sound matters here.
 */
class SmartTube(private val app: Context, private val code: ApkCode) {

    private var player: Any? = null
    private var session: Any? = null
    private var connector: Any? = null
    /** Its session as other apps see it, while a video is open. */
    var bridge: SessionBridge? = null
        private set

    private fun make(className: String, vararg args: Any?): Any {
        val c = code.loadClass(className)
        val ctor = c.constructors.first { it.parameterCount == args.size &&
            it.parameterTypes.zip(args).all { (t, a) -> a == null || box(t).isInstance(a) } }
        return ctor.newInstance(*args)
    }

    private fun box(t: Class<*>): Class<*> = when (t) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        else -> t
    }

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val m = javaClass.methods.first { it.name == name && it.parameterCount == args.size &&
            it.parameterTypes.zip(args).all { (t, a) -> a == null || box(t).isInstance(a) } }
        return m.apply { isAccessible = true }.invoke(this, *args)
    }

    /** A video is opened and starts playing (with SmartTube's default settings: it takes the speakers, its session is on). */
    fun open(url: String) {
        // PlaybackFragment.createPlayer()
        val selector = make("com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector",
            make("com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection\$Factory"))
        val renderers = make("com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory", app)
        val player = make("com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer", app)
            .call("createPlayer", app, renderers, selector)!!
        this.player = player
        // PlaybackFragment.createMediaSession()
        val session = make("android.support.v4.media.session.MediaSessionCompat", app, PACKAGE)
        this.session = session
        session.call("setActive", true)
        val connector = make("com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector", session)
        this.connector = connector
        connector.call("setPlayer", player)
        connector.call("setQueueNavigator", make("com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.BackboneQueueNavigator"))
        bridge = SessionBridge(app, session, PACKAGE).also { it.listActive(true) }
        // The video itself.
        val source = make("com.google.android.exoplayer2.source.ProgressiveMediaSource\$Factory",
            make("com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory", "SmartTube"))
            .call("createMediaSource", Uri.parse(url))
        player.call("prepare", source)
        player.call("setPlayWhenReady", true)
    }

    /** The video's closed (PlaybackFragment's releasePlayer). */
    fun close() {
        connector?.call("setPlayer", null)
        session?.call("setActive", false)
        session?.call("release")
        bridge?.listActive(false)
        player?.call("release")
        player = null; session = null; connector = null; bridge = null
    }

    /** Playing (or about to, once it's loaded). */
    val playWhenReady: Boolean get() = player?.call("getPlayWhenReady") == true

    /** How far into the video, in ms. */
    val position: Long get() = (player?.call("getCurrentPosition") as Long?) ?: 0L

    /** ExoPlayer's state: 1 idle, 2 buffering, 3 ready, 4 ended. */
    val state: Int get() = (player?.call("getPlaybackState") as Int?) ?: 1

    companion object {
        const val PACKAGE = "org.smarttube.stable"
    }
}
