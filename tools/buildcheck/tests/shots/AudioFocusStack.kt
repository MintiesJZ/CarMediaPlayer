package shots

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * Android passing audio focus between apps, which Robolectric doesn't do (it
 * only notes the last request): when an app asks for the speakers, the app
 * that had them hears it lost them, for good or for a moment; when an app
 * that took them for a moment gives them up, the one before gets them back.
 * Call [check] as time goes by (it looks for new requests and give-ups).
 */
class AudioFocusStack(context: Context) {

    private class Holder(val listener: AudioManager.OnAudioFocusChangeListener?, val request: AudioFocusRequest?, val gain: Int)

    private val audio = shadowOf(context.getSystemService(AudioManager::class.java))
    private val stack = ArrayDeque<Holder>()
    /** Focus changes told to apps, in order: "<listener> LOSS", … */
    val told: MutableList<String> = mutableListOf()

    private fun tell(h: Holder, change: Int) {
        val listener = h.listener ?: return
        told += "${listener.javaClass.name.substringAfterLast('.')} ${name(change)}"
        val handler = h.request?.let { ApkCode.get(it, "mListenerHandler") as Handler? } ?: Handler(Looper.getMainLooper())
        handler.post { listener.onAudioFocusChange(change) }
    }

    private fun name(change: Int) = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "$change"
    }

    private fun same(h: Holder, listener: Any?) = h.listener != null && h.listener === listener

    fun check() {
        // Each request and give-up is taken (and cleared) as it's seen: apps reuse their request objects.
        val request = audio.lastAudioFocusRequest
        if (request != null) {
            ApkCode.set(audio, "lastAudioFocusRequest", null)
            val holder = Holder(request.listener, request.audioFocusRequest, request.durationHint)
            val top = stack.lastOrNull()
            if (top != null && !same(top, holder.listener)) {
                when (holder.gain) {
                    AudioManager.AUDIOFOCUS_GAIN -> { tell(top, AudioManager.AUDIOFOCUS_LOSS); stack.remove(top) }
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> tell(top, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                    else -> tell(top, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                }
            }
            stack.removeAll { same(it, holder.listener) }
            stack.addLast(holder)
        }
        val abandonedRequest = audio.lastAbandonedAudioFocusRequest
        val abandonedListener = audio.lastAbandonedAudioFocusListener
        val listener = when {
            abandonedRequest != null -> ApkCode.get(abandonedRequest, "mFocusListener")
            else -> abandonedListener
        }
        if (abandonedRequest != null || abandonedListener != null) {
            ApkCode.set(audio, "lastAbandonedAudioFocusRequest", null)
            ApkCode.set(audio, "lastAbandonedAudioFocusListener", null)
            val gone = stack.firstOrNull { same(it, listener) }
            if (gone != null) {
                val wasTop = stack.lastOrNull() === gone
                stack.remove(gone)
                // Only an app that lost it for a moment is still waiting for it back.
                if (wasTop) stack.lastOrNull()?.let { tell(it, AudioManager.AUDIOFOCUS_GAIN) }
            }
        }
    }

    /** Who has the speakers now (their listener's class), if anyone. */
    val holder: String? get() = stack.lastOrNull()?.listener?.javaClass?.name
}
