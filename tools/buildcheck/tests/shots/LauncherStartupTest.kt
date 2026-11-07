package shots

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.media.MediaFormat
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.PlaybackSettings
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaCache
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.Collections

/**
 * The car starting, as the launcher sees it: the SAIC launcher's own start-up
 * code (ScreenStateMachine's "last mode" restore, ModeManager's commands,
 * LastModeManager, SAIC's CommandParcelable — converted from the firmware,
 * see tools/firmware/README.md) brings back the last source with the app's
 * real MediaService.  Commands go between them as parcels, so SAIC's class
 * and the app's copy of it must agree, and the launcher finds the app's
 * service the way it does on the head unit (the first service in the package).
 *
 * Skipped unless the launcher's code was converted for this run
 * (FIRMWARE_LAUNCHER_APK).  Stood in for: Android delivering startService
 * intents between the two apps, the launcher's home screen and the car's
 * power (their "ready" events, sent at a typical moment), and its work thread
 * (the main thread here).
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class LauncherStartupTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var code: ApkCode
    private lateinit var server: FakeNavidrome
    private var s: MediaService? = null
    private lateinit var mm: Any
    private var startId = 1
    /** When the launcher started (elapsedRealtime). */
    private var bootAt = 0L

    /** What the launcher sent to apps, in order, with when (ms after it started): "1234 media 32773 type 0". */
    private val launcherSent = Collections.synchronizedList(mutableListOf<String>())
    /** What the app sent the launcher. */
    private val appSent = Collections.synchronizedList(mutableListOf<String>())
    /** The app's commands to the launcher are held back until this long after the launcher starts. */
    private var appHeardAfter = 0L
    private val heldBack = mutableListOf<Intent>()

    @Before
    fun setUp() {
        val launcher = ApkCode.launcher(javaClass.classLoader!!)
        assumeTrue("the launcher's code wasn't converted for this run (FIRMWARE_LAUNCHER_APK)", launcher != null)
        if (System.getenv("LAUNCHER_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        code = launcher!!
        val f = MediaCache::class.java.getDeclaredField("cache").apply { isAccessible = true }
        (f.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        f.set(null, null)
        server = FakeNavidrome()
        server.addSongs(3, seconds = 60)
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "secret"))
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, "audio/opus") }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("fake.opus").setIsEncoder(false)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(format).setIsEncoder(false).build())
                .build())
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        ShadowMediaCodec.addDecoder("fake.opus", ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024) { input, output ->
            input.position(input.limit())
            output.put(ByteArray(3840))
        })
        // The launcher finds an app's service by asking for any service in the
        // package and taking the first.  Android lists them in manifest order
        // (Robolectric sorts them by name), so it's answered that way here.
        answerServiceQuery(app.packageName, appServicesInManifestOrder())
        // The radio app, so the launcher can fall back to it.
        answerServiceQuery(RADIO, listOf("$RADIO.RadioService"))
        // The launcher's home screen is in front (it checks it isn't the setup guide).
        val task = ActivityManager.RunningTaskInfo().apply {
            topActivity = ComponentName(LAUNCHER, "$LAUNCHER.view.MainActivity")
        }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setTasks(listOf(task))
    }

    @After
    fun tearDown() {
        s?.onDestroy()
        if (::server.isInitialized) server.close()
    }

    /** The app's services that have an intent filter (the ones an implicit query can find), in manifest order. */
    private fun appServicesInManifestOrder(): List<String> {
        val manifest = java.io.File(System.getProperty("app.manifest")!!)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val pkg = app.packageName
        val services = doc.getElementsByTagName("service")
        return (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .filter { it.getElementsByTagName("intent-filter").length > 0 }
            .map { it.getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
            .map { if (it.startsWith(".")) pkg + it else it }
    }

    private fun answerServiceQuery(pkg: String, services: List<String>) {
        val pm = shadowOf(app.packageManager)
        val infos = services.map { name ->
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = pkg
                    this.name = name
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
            }
        }
        pm.addResolveInfoForIntent(Intent().setPackage(pkg), infos)
    }

    // ── the launcher ─────────────────────────────────────────────────────

    /** The launcher's own context: its resource lists (full-screen apps) aren't in this app, so they're empty. */
    private val launcherContext: Context by lazy {
        val base = app
        @Suppress("DEPRECATION")
        val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getStringArray(id: Int): Array<String> = emptyArray()
        }
        object : ContextWrapper(base) {
            override fun getResources(): Resources = res
            override fun getApplicationContext(): Context = this
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size &&
                it.parameterTypes.zip(args).all { (t, a) -> a == null || t.isInstance(a) || t.isPrimitive } }
                ?.let { return it.apply { isAccessible = true }.invoke(target, *args) }
            c = c.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun lastModeManager(): Any =
        code.loadClass("com.saicmotor.launcher.model.LastModeManager")
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(launcherContext)

    /** What the launcher saved as the car was switched off: the last source, and whether it was playing. */
    private fun savedAtSwitchOff(pkg: String = MEDIA, type: Int = 0, playing: Boolean = true) {
        val last = lastModeManager()
        call(last, "saveLastSource", pkg, type)
        call(last, "saveLastSourcePlayState", playing)
        // SharedPreferences.apply(): let it land.
        looper.idle()
    }

    /**
     * The launcher's service starts (as the head unit boots): its ModeManager
     * and screen state machine, which waits for the last source.  Like
     * ModeManager's constructor it asks the media app what can play, and
     * like LauncherService it starts the media app's service.
     */
    private fun launcherStarts() {
        bootAt = SystemClock.elapsedRealtime()
        mm = code.bare("com.saicmotor.launcher.model.ModeManager")
        val set = { name: String, value: Any? -> ApkCode.set(mm, name, value) }
        set("mAppContext", launcherContext)
        set("mLastModeManager", lastModeManager())
        val callbackIface = code.loadClass("com.saicmotor.launcher.ILauncherCallback")
        set("mLauncherCallback", Proxy.newProxyInstance(code, arrayOf(callbackIface)) { proxy, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.get(0)
                "toString" -> "launcher UI"
                else       -> defaultFor(m.returnType)
            }
        })
        val screenCallback = code.loadClass("com.saicmotor.launcher.model.ModeManager\$2")
            .getDeclaredConstructor(mm.javaClass).apply { isAccessible = true }.newInstance(mm)
        set("mScreenCallback", screenCallback)
        val machine = code.loadClass("com.saicmotor.launcher.model.ScreenStateMachine").getMethod("make",
            Context::class.java, code.loadClass("com.saicmotor.launcher.model.ScreenStateMachine\$IStateEventCallback"),
            Looper::class.java, Integer.TYPE).invoke(null, launcherContext, screenCallback, Looper.getMainLooper(), MG4)
        set("mScreenStateMachine", machine)
        call(mm, "sendCommand", UPDATE_SOURCE_AVAILABLE, MEDIA, 0)
        val utils = code.loadClass("com.saicmotor.launcher.model.utils.LaunchUtils")
        val service = utils.getMethod("getAppService", String::class.java, Context::class.java).invoke(null, MEDIA, launcherContext) as String?
        if (service != null) launcherContext.startService(Intent().setComponent(ComponentName(MEDIA, service)))
        deliver()
    }

    /** One of the launcher's "system ready" events: 1 = the car's power (3 = on), 2 = start-up done, 3 = the home screen's ready. */
    private fun systemReady(type: Int, state: Int) {
        call(mm, "sendScreenEvent", SYSTEM_READY, type, state, LAUNCHER)
        deliver()
    }

    /** The car's power comes on, then the launcher's home screen shows. */
    private fun powerOnThenHomeScreen() {
        drive(1_000)
        systemReady(1, 3)
        drive(3_000)
        systemReady(2, 1)
        systemReady(3, 1)
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    // ── between the two (Android's part) ─────────────────────────────────

    private val ms get() = SystemClock.elapsedRealtime() - bootAt

    /** A command passed as Android passes it: written by one app's class, read by the other's. */
    private fun reparcel(command: Parcelable, creator: Parcelable.Creator<*>): Any {
        val p = Parcel.obtain()
        try {
            command.writeToParcel(p, 0)
            p.setDataPosition(0)
            return creator.createFromParcel(p)!!
        } finally {
            p.recycle()
        }
    }

    private val saicCreator by lazy {
        code.loadClass("com.saicmotor.common.CommandParcelable").getField("CREATOR").get(null) as Parcelable.Creator<*>
    }

    private fun describe(c: Any) = "${call(c, "getEvent")} type ${call(c, "getTargetType")} state ${call(c, "getTargetState")}"

    /** Hands each startService intent to the app it's for. */
    private fun deliver() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            val to = intent.component ?: continue
            @Suppress("DEPRECATION")
            val command = intent.getParcelableExtra<Parcelable>(CommandParcelable.COMMAND_KEY)
            when {
                to.packageName == LAUNCHER -> { heldBack += intent }
                to.packageName == app.packageName -> {
                    if (command != null && command.javaClass.classLoader !== code) continue   // the app's own
                    val what = if (command == null) "start" else describe(command)
                    launcherSent += "$ms ${to.shortClassName.substringAfterLast('.')} $what"
                    val service = s ?: Robolectric.buildService(MediaService::class.java).create().get().also {
                        s = it
                        it.callerForTest = LAUNCHER
                    }
                    assertEquals("the launcher finds the app's MediaService", MediaService::class.java.name, to.className)
                    val forApp = Intent(intent).apply {
                        if (command != null) putExtra(CommandParcelable.COMMAND_KEY, reparcel(command, CommandParcelable.CREATOR) as CommandParcelable)
                    }
                    service.onStartCommand(forApp, 0, startId++)
                }
                else -> {
                    launcherSent += "$ms ${to.packageName.substringAfterLast('.')} ${command?.let(::describe) ?: "start"}"
                    if (to.packageName == RADIO && command != null) radioComesOn(playing = call(command, "getEvent") == SWITCH_PLAY)
                }
            }
        }
        if (ms >= appHeardAfter && heldBack.isNotEmpty()) {
            val now = heldBack.toList()
            heldBack.clear()
            for (intent in now) {
                @Suppress("DEPRECATION")
                val command = intent.getParcelableExtra<CommandParcelable>(CommandParcelable.COMMAND_KEY) ?: continue
                appSent += "$ms ${command.event} type ${command.targetType} state ${command.targetState}"
                val forLauncher = Intent(intent).putExtra(CommandParcelable.COMMAND_KEY, reparcel(command, saicCreator) as Parcelable)
                call(mm, "processEvent", forLauncher)
            }
        }
    }

    // ── the app ──────────────────────────────────────────────────────────

    private fun MediaService.player(): ExoPlayer = ApkCode.get(this, "player") as ExoPlayer
    private val playing get() = s?.player()?.playWhenReady == true

    /** What the app was playing when the car was switched off. */
    private fun appWasPlayingAnAlbum() {
        val store = ResumeStore(app)
        val items = server.songs.values.map {
            ResumeStore.SavedItem("sub:${it.id}", it.title, "Artist", "Album", null, "al1", "ar1", it.seconds * 1_000L)
        }
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId("al1"))
        store.saveItem(items[1], 5_000L)
        store.saveSnapshot(items)
    }

    private fun drive(ms: Long, until: () -> Boolean = { false }) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < ms && !until()) {
            looper.idleFor(Duration.ofMillis(10))
            deliver()
            Thread.sleep(1)
        }
    }

    /**
     * The radio as the launcher brings it back: its session shows up, playing
     * or (asked for "paused") selected but quiet.
     */
    private fun radioComesOn(playing: Boolean) {
        val iface = Class.forName("android.media.session.ISessionController")
        val binder = android.os.Binder()
        val radio = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, m, args ->
            when (m.name) {
                "getPackageName" -> RADIO
                "getTag"         -> "radio"
                "asBinder"       -> binder
                "hashCode"       -> System.identityHashCode(proxy)
                "equals"         -> proxy === args?.get(0)
                "toString"       -> "the radio's session"
                else             -> defaultFor(m.returnType)
            }
        }
        val token = MediaSession.Token::class.java.getDeclaredConstructor(iface).apply { isAccessible = true }.newInstance(radio)
        val controller = MediaController(app, token)
        shadowOf(controller).setPackageName(RADIO)
        shadowOf(controller).setPlaybackState(PlaybackState.Builder()
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f).build())
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
        radioPlaying = playing
    }
    private var radioPlaying: Boolean? = null

    /** Android tells the app the head unit has booted (BootReceiver → MediaService). */
    private fun bootReceiverFires() {
        val service = s ?: Robolectric.buildService(MediaService::class.java).create().get().also {
            s = it
            it.callerForTest = LAUNCHER
        }
        service.onStartCommand(Intent(app, MediaService::class.java).setAction(MediaService.ACTION_BOOT_START), 0, startId++)
    }

    private fun restores() = launcherSent.filter { it.contains(" $SWITCH_PLAY ") || it.contains(" $SWITCH_PAUSE ") || it.contains(" $SWITCH ") }

    // ── tests ────────────────────────────────────────────────────────────

    /** The launcher sends its commands to the first service in the package: it must be MediaService. */
    @Test
    fun mediaServiceIsTheFirstServiceInTheManifest() {
        assertEquals(MediaService::class.java.name, appServicesInManifestOrder().first())
    }

    @Test
    fun theMusicIsBroughtBackAtStartUp() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(playing = true)
        launcherStarts()
        powerOnThenHomeScreen()
        drive(30_000) { playing && s!!.player().isPlaying }
        println("launcher sent: $launcherSent\napp sent: $appSent")
        assertTrue("the app says its music can play (USB1)", appSent.any { it.endsWith("$UPDATE_SOURCE_AVAILABLE type 0 state 1") })
        assertEquals("brought back once, playing", 1, restores().size)
        assertTrue(restores()[0].contains("MediaService $SWITCH_PLAY type 0"))
        assertFalse("not the radio", launcherSent.any { it.contains("radio") })
        assertTrue("playing", s!!.player().isPlaying)
        assertEquals("the song it was on", 1, s!!.player().currentMediaItemIndex)
    }

    /** The car pauses the music as it's switched off, so the launcher asks for it paused: it plays anyway. */
    @Test
    fun pausedAtSwitchOffStillPlays() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(playing = false)
        launcherStarts()
        powerOnThenHomeScreen()
        drive(30_000) { playing && s!!.player().isPlaying }
        println("launcher sent: $launcherSent")
        assertEquals(1, restores().size)
        assertTrue(restores()[0].contains("MediaService $SWITCH_PAUSE type 0"))
        assertTrue("playing", s!!.player().isPlaying)
    }

    /** "Don't play anything": the music is the source, paused — not the radio. */
    @Test
    fun dontPlayAnythingStaysQuietButIsTheSource() {
        PlaybackSettings.setStartupMode(app, PlaybackSettings.StartupMode.NOTHING)
        appWasPlayingAnAlbum()
        savedAtSwitchOff(playing = true)
        launcherStarts()
        powerOnThenHomeScreen()
        drive(30_000)
        println("launcher sent: $launcherSent")
        assertEquals(1, restores().size)
        assertFalse("not the radio", launcherSent.any { it.contains("radio") })
        assertFalse("quiet", playing)
        assertEquals("the queue is ready", 3, s!!.player().mediaItemCount)
    }

    /** The app's answer comes late: the launcher keeps asking (every 3 s) and brings it back when it hears. */
    @Test
    fun aLateAnswerIsWaitedFor() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(playing = true)
        appHeardAfter = 15_000
        launcherStarts()
        powerOnThenHomeScreen()
        drive(40_000) { playing && s!!.player().isPlaying }
        println("launcher sent: $launcherSent\napp sent: $appSent")
        assertEquals(1, restores().size)
        assertFalse("not the radio", launcherSent.any { it.contains("radio") })
        assertTrue(s!!.player().isPlaying)
    }

    /** No answer at all: after its retries the launcher goes to the radio (what happened before the app answered). */
    @Test
    fun noAnswerMeansTheRadio() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(playing = true)
        appHeardAfter = Long.MAX_VALUE
        launcherStarts()
        powerOnThenHomeScreen()
        drive(40_000) { launcherSent.any { it.contains("radio") } }
        println("launcher sent: $launcherSent")
        assertTrue("the radio", launcherSent.any { it.contains("radio $SWITCH_PLAY") || it.contains("radio $SWITCH_PAUSE") })
        assertEquals("not the music", 0, restores().count { it.contains("MediaService") })
    }

    /** The radio was on at switch-off, and comes back playing: the music leaves it alone. */
    @Test
    fun lastOnTheRadioStaysOnTheRadio() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(pkg = RADIO, type = 0, playing = true)
        launcherStarts()
        drive(1_000)
        bootReceiverFires()
        powerOnThenHomeScreen()
        drive(40_000)
        println("launcher sent: $launcherSent")
        assertTrue("the radio's back", launcherSent.any { it.contains("radio $SWITCH_PLAY") })
        assertEquals("not the music", 0, restores().count { it.contains("MediaService") })
        assertFalse("the music stays quiet", playing)
    }

    /**
     * The radio was the source at switch-off, but paused (the car pauses
     * whatever's playing as it's switched off, and the launcher asks for it
     * paused): the music still stays quiet — the driver chose the radio.
     */
    @Test
    fun lastOnTheRadioPausedTheMusicStaysQuiet() {
        appWasPlayingAnAlbum()
        savedAtSwitchOff(pkg = RADIO, type = 0, playing = false)
        launcherStarts()
        drive(1_000)
        bootReceiverFires()
        powerOnThenHomeScreen()
        drive(40_000)
        println("launcher sent: $launcherSent")
        assertTrue("the radio's back, paused", launcherSent.any { it.contains("radio $SWITCH_PAUSE") })
        assertFalse("the music stays quiet", playing)
    }

    private companion object {
        const val LAUNCHER = "com.saicmotor.launcher"
        const val MEDIA = "com.saicmotor.media"
        const val RADIO = "com.saicmotor.radio"
        const val MG4 = 69
        const val SYSTEM_READY = 16392
        const val UPDATE_SOURCE_AVAILABLE = 16417
        const val SWITCH = 32769
        const val SWITCH_PLAY = 32773
        const val SWITCH_PAUSE = 32774
    }
}
