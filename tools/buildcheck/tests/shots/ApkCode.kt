package shots

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader

/**
 * Another app's own code, converted from its APK with dex2jar for this run
 * (see tools/firmware/README.md; it's never in the repo): the SAIC launcher
 * ([launcher]) or SmartTube ([smartTube]).  The classes it [owns] come from
 * the converted jar — its own copies, as in its own process; Android's and
 * everything else from the test, so it talks to the app's real service.
 */
class ApkCode private constructor(jar: File, parent: ClassLoader, private val owns: ApkCode.(String) -> Boolean) :
    URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (!owns(name)) return super.loadClass(name, resolve)
        val c = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(c)
        c
    }

    /**
     * The converted classes have no stack map frames (dex has nothing to make
     * them from), which Java's verifier needs: they're worked out here.
     */
    override fun findClass(name: String): Class<*> {
        val bytes = classBytes(name.replace('.', '/')) ?: standIn(name) ?: throw ClassNotFoundException(name)
        val reader = ClassReader(bytes)
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getCommonSuperClass(a: String, b: String): String = commonSuperClass(a, b)
        }
        reader.accept(writer, 0)
        val fixed = writer.toByteArray()
        return defineClass(name, fixed, 0, fixed.size)
    }

    /** Android classes it names that aren't in Android 9 (SAIC's own additions): stood in for, empty, as they're never used here. */
    val standIns = mutableListOf<String>()

    private fun standIn(name: String): ByteArray? {
        if (!name.startsWith("android.")) return null
        standIns += name
        val w = ClassWriter(0)
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            name.replace('.', '/'), null, "java/lang/Object", null)
        w.visitEnd()
        return w.toByteArray()
    }

    /** From the jar only: the app has classes of the same name (CommandParcelable, the media library). */
    private fun classBytes(internalName: String): ByteArray? =
        findResource("$internalName.class")?.openStream()?.use { it.readBytes() }

    private fun inJar(name: String) = findResource(name.replace('.', '/') + ".class") != null

    /** A class's superclass and whether it's an interface, without loading it (it may be the one being made). */
    private fun describe(internalName: String): Pair<String?, Boolean> {
        if (owns(internalName.replace('/', '.'))) {
            val r = ClassReader(classBytes(internalName) ?: return "java/lang/Object" to false)
            return r.superName to (r.access and Opcodes.ACC_INTERFACE != 0)
        }
        val c = try { Class.forName(internalName.replace('/', '.'), false, parent) } catch (_: Throwable) { return "java/lang/Object" to false }
        return c.superclass?.name?.replace('.', '/') to c.isInterface
    }

    private fun ancestors(internalName: String): List<String> {
        val out = ArrayList<String>()
        var t: String? = internalName
        while (t != null) { out += t; t = describe(t).first }
        return out
    }

    private fun commonSuperClass(a: String, b: String): String {
        if (describe(a).second || describe(b).second) return "java/lang/Object"
        val ofB = ancestors(b).toSet()
        return ancestors(a).firstOrNull { it in ofB } ?: "java/lang/Object"
    }

    /** An instance of [className] made without running its constructor (the launcher's need the whole car). */
    fun bare(className: String): Any = UNSAFE_ALLOCATE.invoke(UNSAFE, loadClass(className))

    companion object {
        /** The launcher's own packages and the car library it carries. */
        private val SAIC = listOf("com.saicmotor.launcher.", "com.saicmotor.carapi.", "com.saicmotor.adapterservice.", "com.saicmotor.ev.",
            // SAIC's own CommandParcelable etc., not the app's copy: commands cross between the two as parcels.
            "com.saicmotor.common.")

        /** Android's own classes, shared by every app (android.support is a library, not Android). */
        private fun android(name: String) =
            (name.startsWith("android.") && !name.startsWith("android.support.")) ||
                listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "dalvik.", "com.android.", "org.json.", "org.w3c.",
                    "org.xml.", "org.xmlpull.", "org.apache.http.").any { name.startsWith(it) }

        private fun jar(property: String): File? = System.getProperty(property)?.let(::File)?.takeIf { it.isFile }

        /** The SAIC launcher, if it was converted for this run (FIRMWARE_LAUNCHER_APK). */
        fun launcher(parent: ClassLoader): ApkCode? =
            jar("launcher.jar")?.let { ApkCode(it, parent) { name -> SAIC.any { name.startsWith(it) } } }

        /** SmartTube, if it was converted for this run (SMARTTUBE_APK): every class it carries is its own copy. */
        fun smartTube(parent: ClassLoader): ApkCode? =
            jar("smarttube.jar")?.let { ApkCode(it, parent) { name -> !android(name) && inJar(name) } }

        private val UNSAFE = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        private val UNSAFE_ALLOCATE = UNSAFE.javaClass.getMethod("allocateInstance", Class::class.java)

        fun set(target: Any, field: String, value: Any?) {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try { c.getDeclaredField(field).apply { isAccessible = true }.set(target, value); return } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            throw NoSuchFieldException(field)
        }

        fun get(target: Any, field: String): Any? {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try { return c.getDeclaredField(field).apply { isAccessible = true }.get(target) } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            throw NoSuchFieldException(field)
        }
    }
}
