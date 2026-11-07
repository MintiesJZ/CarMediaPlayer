package shots

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/**
 * An app's MediaSessionCompat as Android shows it to other apps: a
 * MediaController.  Robolectric's sessions don't connect apps, so this does
 * what Android would: commands reach the session's callback on the main
 * thread, and the state, metadata and end the session last set are passed on
 * to the controller and whoever's watching it.  It works with any app's own
 * copy of the media library (by reflection).
 */
class SessionBridge(private val context: Context, private val session: Any, private val pkg: String) {

    /** The commands sent to the session, in order ("play", "pause", …). */
    val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val main = Handler(Looper.getMainLooper())
    private val impl = ApkCode.get(session, "mImpl")!!
    private val binder = Binder()

    /** The callback Android calls (the media library's, which passes it on to the app's): as it is now. */
    private fun callback(): MediaSession.Callback? {
        val compat = method(impl, "getCallback").invoke(impl) ?: return null
        return ApkCode.get(compat, "mCallbackFwk") as MediaSession.Callback?
    }

    private fun deliver(what: String, call: MediaSession.Callback.() -> Unit): Any? {
        sent += what
        main.post { callback()?.call() }
        return null
    }

    val controller: MediaController = run {
        val iface = Class.forName("android.media.session.ISessionController")
        val sessionController = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, m, args ->
            when (m.name) {
                "play"             -> deliver("play") { onPlay() }
                "pause"            -> deliver("pause") { onPause() }
                "stop"             -> deliver("stop") { onStop() }
                "next"             -> deliver("next") { onSkipToNext() }
                "previous"         -> deliver("previous") { onSkipToPrevious() }
                "fastForward"      -> deliver("fastForward") { onFastForward() }
                "rewind"           -> deliver("rewind") { onRewind() }
                "seekTo"           -> (args!![2] as Long).let { to -> deliver("seekTo $to") { onSeekTo(to) } }
                "sendCustomAction" -> (args!![2] as String).let { action ->
                    deliver(action) { onCustomAction(action, args[3] as Bundle?) } }
                "getPackageName"   -> pkg
                "getTag"           -> pkg
                // Whoever watches it is told of changes through Robolectric's MediaController (below).
                "registerCallbackListener", "unregisterCallbackListener" -> null
                "asBinder"         -> binder
                "hashCode"         -> System.identityHashCode(proxy)
                "equals"           -> proxy === args?.get(0)
                "toString"         -> "$pkg's session"
                else               -> throw AssertionError("asked $pkg's session for ${m.name}: not stood in for")
            }
        }
        val token = MediaSession.Token::class.java.getDeclaredConstructor(iface).apply { isAccessible = true }.newInstance(sessionController)
        MediaController(context, token)
    }

    private val shadow = shadowOf(controller)

    private fun stateOf(compat: Any?) = compat?.let { method(it, "getPlaybackState").invoke(it) } as PlaybackState?
    private fun metadataOf(compat: Any?) = compat?.let { method(it, "getMediaMetadata").invoke(it) } as MediaMetadata?

    private var metadataSeen: Any? = null

    /**
     * Passes on new metadata: the media library tells its own listeners of
     * state changes but leaves metadata to Android, so it's looked for here
     * (call as time goes by, or use [syncAll]).
     */
    fun sync() {
        val now = ApkCode.get(impl, "mMetadata")
        if (now === metadataSeen) return
        metadataSeen = now
        val md = metadataOf(now)
        shadow.setMetadata(md)
        main.post { shadow.executeOnMetadataChanged(md) }
    }

    init {
        shadow.setPackageName(pkg)
        shadow.setPlaybackState(stateOf(ApkCode.get(impl, "mPlaybackState")))
        metadataSeen = ApkCode.get(impl, "mMetadata")
        shadow.setMetadata(metadataOf(metadataSeen))
        live += this
        // Told of every change as the session makes it (the media library's own listener).
        val loader = session.javaClass.classLoader!!
        val listenerIface = loader.loadClass("android.support.v4.media.session.IMediaControllerCallback")
        val listener = Proxy.newProxyInstance(loader, arrayOf(listenerIface)) { proxy, m, args ->
            when (m.name) {
                "onPlaybackStateChanged" -> stateOf(args!![0]).let { s ->
                    shadow.setPlaybackState(s); main.post { shadow.executeOnPlaybackStateChanged(s) }; null }
                "onMetadataChanged" -> metadataOf(args!![0]).let { md ->
                    shadow.setMetadata(md); main.post { shadow.executeOnMetadataChanged(md) }; null }
                "onSessionDestroyed" -> { main.post { shadow.executeOnSessionDestroyed() }; null }
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.get(0)
                "toString" -> "watching $pkg's session"
                else       -> null
            }
        }
        val token = method(session, "getSessionToken").invoke(session)
        val extra = method(token, "getExtraBinder").invoke(token)!!
        loader.loadClass("android.support.v4.media.session.IMediaSession")
            .getMethod("registerCallbackListener", listenerIface).invoke(extra, listener)
    }

    companion object {
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<SessionBridge, Boolean>())

        /** [sync] for every session shown to other apps. */
        fun syncAll() = live.toList().forEach { it.sync() }
    }

    private fun method(target: Any, name: String) =
        target.javaClass.methods.first { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }

    /** The session is listed among Android's active sessions, for apps that watch them. */
    fun listActive(on: Boolean) {
        val manager = shadowOf(context.getSystemService(MediaSessionManager::class.java))
        if (on) { manager.addController(controller); return }
        @Suppress("UNCHECKED_CAST")
        val controllers = ApkCode.get(manager, "controllers") as MutableList<MediaController>
        controllers.remove(controller)
        @Suppress("UNCHECKED_CAST")
        val listeners = ApkCode.get(manager, "listeners") as Set<MediaSessionManager.OnActiveSessionsChangedListener>
        val now = controllers.toList()
        listeners.forEach { it.onActiveSessionsChanged(now) }
    }
}
