import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android Gradle plugin.
    id("dev.flutter.flutter-gradle-plugin")
}

// Signing material lives outside version control. When it is missing the build
// still succeeds and falls back to the debug key, so a checkout without the
// release keystore can be built and run.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseStoreFile = keystoreProperties.getProperty("storeFile")?.let(::file)
val hasReleaseSigning = listOf("keyAlias", "keyPassword", "storePassword").all {
    !keystoreProperties.getProperty(it).isNullOrBlank()
} && releaseStoreFile?.isFile == true

// Android 6.0 (API 23) is the floor for the old TV boxes this build ships to.
// The value must live in a named constant: Flutter's build-time minSdk migrator
// rewrites a literal `minSdk = 23` back to `flutter.minSdkVersion` (24 as of
// Flutter 3.47). API 23 is still the lowest version the Flutter 3.47 engine
// supports — the Flutter Gradle plugin fails the build below 23 and only warns
// at 23 — so pinning it here is safe, it just logs a warning on every build.
val android6MinSdk = 23

android {
    namespace = "com.mystyle.purelive.tv"

    buildFeatures {
        buildConfig = true
    }

    // Pinned rather than inherited so the TV build does not silently shift when
    // the Flutter SDK bumps its defaults.
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    // libvulkan.so stub for Android 6.0 (API 23) — see src/main/cpp/vulkan_stub.c
    // for why the player stack needs it to keep plugin registration alive.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.mystyle.purelive.tv"
        // android6MinSdk (23, Android 6.0) instead of flutter.minSdkVersion so the
        // APK stays installable on old Marshmallow TV boxes. See the comment on
        // the constant for why a literal 23 cannot be written here directly.
        minSdk = android6MinSdk
        targetSdk = 37
        multiDexEnabled = true
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Per-ABI APKs come from the --split-per-abi flag (tool/build_tv_apks.ps1).
    // A static splits block here cannot coexist with the Flutter Gradle plugin:
    // on a plain `flutter build apk` it force-feeds defaultConfig.ndk.abiFilters
    // with all three of its ABIs, which AGP 9 rejects as conflicting with
    // splits, and the flag itself reset()s a static block anyway.
    // No x86_64 here either — TV hardware is arm only.

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
        // Android 6.0 (API 23) verifies JAR (v1) signatures only — a v2-only APK
        // will not install on Marshmallow boxes. Pin both schemes instead of
        // relying on AGP's minSdk-based default, and pin it on the debug key too
        // since it signs release builds when no keystore is configured.
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseSigning) {
            getByName("release") {
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

// Two renderer variants share one applicationId, so a device treats them
// as the same app and the user simply picks which APK to install:
//   impeller — engine default renderer, no manifest override
//   skia     — legacy renderer for boxes where Impeller misbehaves
//   (the EnableImpeller opt-out lives in src/skia/AndroidManifest.xml and
//   only merges into the skia variant)
flavorDimensions += "renderer"
productFlavors {
    create("impeller") { dimension = "renderer" }
    create("skia") { dimension = "renderer" }
}

// Scan dependencies' bytecode too, not just this module's sources: the Flutter
// embedding and the plugin AARs are where unguarded newer-API calls hide on
// the Android 6.0 floor.
lint {
    checkDependencies = true
}

    buildTypes {
        // Debug builds reuse the release key when it is available, so a debug
        // APK can replace an installed release build without uninstalling first.
        val signing = if (hasReleaseSigning) {
            signingConfigs.getByName("release")
        } else {
            signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signing
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
        }
        debug {
            signingConfig = signing
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// ---------------------------------------------------------------------------
// Android 6.0 (API 23) native library compatibility patch
//
// Two independent blockers stop prebuilt .so files from loading on Android 6.0
// (API 23); both are reproduced on an API 23 emulator and both are fixed here
// before packaging:
//
// 1. Fortify wrapper imports. The Flutter engine ships libflutter.so linked
//    against the newest NDK libc stub, so its dynamic symbol table carries
//    strong references to bionic fortify wrappers that only exist from API 24
//    onward: __fwrite_chk and __write_chk. Their entries are repointed at the
//    base functions ("fwrite" / "write", exported since API 1). The _chk
//    variants wrap the plain function with an abort-on-overflow check and one
//    extra register argument, which the callee ignores on both arm ABIs, so
//    the rename is behavior-preserving on the happy path and merely skips the
//    fortify check on API 24+ devices.
//
// 2. Symbol version requirements. Since the NDK started shipping versioned
//    libc stubs, prebuilt libraries carry .gnu.version requirements against
//    tags like LIBC_N (Android 7). Android 6's libc has no version
//    definitions at all, so the linker refuses every versioned lookup and
//    dlopen fails with "cannot locate symbol" even for symbols that DO exist
//    on API 23 (e.g. fwrite@LIBC_N). This hits every ABI — including
//    armeabi-v7a, whose engine has no fortify imports. All .gnu.version
//    entries >= 2 are therefore reset to 1 (*global*, unversioned) and the
//    .gnu.version_r auxiliary chains are emptied. Unversioned lookups resolve
//    identically on every Android version.
//
// Runtime-guarded imports (close_range, getifaddrs, ...) are weak symbols and
// resolve to null on old devices by themselves, so they stay untouched.
//
// Wired as a doLast on every merge*NativeLibs task so the patch lands before
// strip/package in every variant. Idempotent: a library with nothing to fix
// keeps its bytes untouched.
// ---------------------------------------------------------------------------

// API 24+ bionic fortify wrappers referenced by prebuilt .so files, mapped to
// the base bionic functions they wrap (present since API 1).
val api23NativeSymbolRenames = mapOf("__fwrite_chk" to "fwrite", "__write_chk" to "write")

// API 24+ imports that have no older equivalent to rename to (getifaddrs /
// freeifaddrs came in with Android 7). Flipping them to weak makes the linker
// resolve them to null on Android 6 instead of failing the whole dlopen; on
// Android 7+ they resolve to the real functions exactly as before. Call sites
// that must not run on API 23 are guarded on the Dart side.
val api23NativeSymbolWeakify = setOf("getifaddrs", "freeifaddrs")

// First index of [needle] inside [haystack] at or after [from], or -1. Kotlin's
// ByteArray.indexOf has no multi-byte pattern overload.
private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from.coerceIn(0, haystack.size)
    var i = from
    outer@ while (i <= haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                i++
                continue@outer
            }
        }
        return i
    }
    return -1
}

// Repoint unresolved dynamic symbol entries named per [renames] to the base
// functions they wrap, and flip entries named in [weakify] to weak imports,
// for ELF files that must also load on Android 6.0.
// Returns true when the file was modified. Only *undefined* (SHN_UNDEF) symbol
// entries are touched; definitions keep their names. Per renamed symbol:
// prefer repointing st_name at an existing NUL-terminated copy of the
// replacement string already in .dynstr (no byte rewritten); otherwise
// overwrite the entry's own string in place, refused when another string
// table reference overlaps the bytes (the replacement is strictly shorter).
private fun patchElfUndSymbols(soFile: File, renames: Map<String, String>, weakify: Set<String> = emptySet()): Boolean {
    val data = soFile.readBytes()
    val elfMagic = byteArrayOf(0x7f.toByte(), 0x45, 0x4c, 0x46)
    if (data.size < 0x40 || !data.copyOfRange(0, 4).contentEquals(elfMagic)) return false
    val is64 = data[4].toInt() == 2
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val eShoff = if (is64) buf.getLong(0x28) else buf.getInt(0x20).toLong() and 0xffffffffL
    val eShentsize = buf.getShort(if (is64) 0x3a else 0x2e).toInt() and 0xffff
    val eShnum = buf.getShort(if (is64) 0x3c else 0x30).toInt() and 0xffff
    if (eShoff <= 0L || eShentsize == 0 || eShnum == 0) return false

    // Section header field offsets differ between the ELF64/ELF32 layouts.
    val shOffsetAt = if (is64) 24 else 16
    val shSizeAt = if (is64) 32 else 20
    val shLinkAt = if (is64) 40 else 24
    val shEntsizeAt = if (is64) 56 else 36

    var symOff = -1L
    var symSize = 0L
    var symEnt = 0
    var strOff = -1L
    var strSize = 0L
    for (i in 0 until eShnum) {
        val base = (eShoff + i.toLong() * eShentsize).toInt()
        if (buf.getInt(base + 4) != 11) continue // SHT_DYNSYM
        symOff = if (is64) buf.getLong(base + shOffsetAt) else buf.getInt(base + shOffsetAt).toLong() and 0xffffffffL
        symSize = if (is64) buf.getLong(base + shSizeAt) else buf.getInt(base + shSizeAt).toLong() and 0xffffffffL
        symEnt = buf.getInt(base + shEntsizeAt)
        val strBase = (eShoff + buf.getInt(base + shLinkAt).toLong() * eShentsize).toInt()
        strOff = if (is64) buf.getLong(strBase + shOffsetAt) else buf.getInt(strBase + shOffsetAt).toLong() and 0xffffffffL
        strSize = if (is64) buf.getLong(strBase + shSizeAt) else buf.getInt(strBase + shSizeAt).toLong() and 0xffffffffL
        break
    }
    if (symOff < 0L || symEnt == 0 || strOff < 0L) return false

    val strTab = data.copyOfRange(strOff.toInt(), (strOff + strSize).toInt())
    // st_name is the first field of both Elf32_Sym and Elf64_Sym; st_info sits
    // at offset 4 (ELF64) or 12 (ELF32); st_shndx at 6 (ELF64) or 14 (ELF32).
    val shndxAt = if (is64) 6 else 14
    val stInfoAt = if (is64) 4 else 12

    // Every name offset referenced from the symbol table, for overlap checks.
    val nameOffsets = mutableSetOf<Int>()
    val pending = mutableListOf<Pair<Int, String>>() // symbol entry offset -> replacement name
    val weakifyTargets = mutableListOf<Int>() // symbol entry offset of st_info
    var cursor = symOff.toInt()
    val end = symOff.toInt() + symSize.toInt()
    while (cursor + symEnt <= end) {
        val nameOff = buf.getInt(cursor)
        val shndx = buf.getShort(cursor + shndxAt).toInt() and 0xffff
        if (nameOff > 0 && nameOff < strTab.size) {
            nameOffsets.add(nameOff)
            if (shndx == 0) { // SHN_UNDEF: an import this file expects resolved
                val nameEnd = indexOfBytes(strTab, byteArrayOf(0), nameOff)
                if (nameEnd > nameOff) {
                    val name = String(strTab, nameOff, nameEnd - nameOff, Charsets.US_ASCII)
                    renames[name]?.let { pending.add(cursor to it) }
                    if (name in weakify) weakifyTargets.add(cursor + stInfoAt)
                }
            }
        }
        cursor += symEnt
    }
    if (pending.isEmpty() && weakifyTargets.isEmpty()) return false

    var modified = false
    // STB_LOCAL=0, STB_GLOBAL=1, STB_WEAK=2 in the high nibble of st_info; the
    // low nibble (symbol type) is preserved.
    for (infoPos in weakifyTargets) {
        val info = buf.get(infoPos).toInt() and 0xff
        if (info shr 4 != 2) {
            buf.put(infoPos, (((info and 0x0f) or 0x20)).toByte())
            modified = true
        }
    }

    for ((symEntry, replacement) in pending) {
        val repBytes = replacement.toByteArray(Charsets.US_ASCII) + 0.toByte()
        // Preferred: reuse an existing NUL-terminated copy of the replacement
        // string that starts right after another string's terminator.
        var at = indexOfBytes(strTab, repBytes, 0)
        var repointed = false
        while (at >= 0) {
            if (at == 0 || strTab[at - 1] == 0.toByte()) {
                buf.putInt(symEntry, at)
                repointed = true
                break
            }
            at = indexOfBytes(strTab, repBytes, at + 1)
        }
        if (repointed) {
            modified = true
            continue
        }
        // Fallback: overwrite the entry's own string in place, only when no
        // other name reference falls inside the rewritten bytes.
        val nameOff = buf.getInt(symEntry)
        val termAt = indexOfBytes(strTab, byteArrayOf(0), nameOff)
        if (termAt < 0) continue
        val oldLen = termAt - nameOff + 1
        if (oldLen < repBytes.size) continue
        val overlaps = nameOffsets.any { other -> other != nameOff && other >= nameOff && other < nameOff + oldLen }
        if (overlaps) continue
        for (b in repBytes.indices) buf.put(nameOff + b, repBytes[b])
        for (i in repBytes.size until oldLen) buf.put(nameOff + i, 0.toByte())
        modified = true
    }
    if (modified) soFile.writeBytes(data)
    return modified
}

// Clears every symbol version requirement in [soFile]: .gnu.version entries
// >= 2 (LIBC, LIBC_N, ...) are reset to 1 (*global*, unversioned) and the
// .gnu.version_r auxiliary chains are emptied, so the dynamic linker performs
// plain name lookups. Needed because Android 6's libc has no version
// definitions, which makes every versioned import unresolvable there. Returns
// true when the file was modified.
private fun clearElfSymbolVersions(soFile: File): Boolean {
    val data = soFile.readBytes()
    val elfMagic = byteArrayOf(0x7f.toByte(), 0x45, 0x4c, 0x46)
    if (data.size < 0x40 || !data.copyOfRange(0, 4).contentEquals(elfMagic)) return false
    val is64 = data[4].toInt() == 2
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val eShoff = if (is64) buf.getLong(0x28) else buf.getInt(0x20).toLong() and 0xffffffffL
    val eShentsize = buf.getShort(if (is64) 0x3a else 0x2e).toInt() and 0xffff
    val eShnum = buf.getShort(if (is64) 0x3c else 0x30).toInt() and 0xffff
    if (eShoff <= 0L || eShentsize == 0 || eShnum == 0) return false

    fun field64(base: Int, at: Int): Long = buf.getLong(base + at)
    fun field32(base: Int, at: Int): Long = buf.getInt(base + at).toLong() and 0xffffffffL

    var dynOff = -1L
    var dynSize = 0L
    var dynEnt = 0
    var versymOff = -1L
    var versymLink = -1
    for (i in 0 until eShnum) {
        val base = (eShoff + i.toLong() * eShentsize).toInt()
        when (buf.getInt(base + 4)) {
            6 -> { // SHT_DYNAMIC
                dynOff = if (is64) field64(base, 24) else field32(base, 24)
                dynSize = if (is64) field64(base, 32) else field32(base, 32)
                dynEnt = buf.getInt(base + 56)
            }
            0x6fffffff -> { // SHT_GNU_versym (the .dynamic *tag* DT_VERSYM is a different value)
                versymOff = if (is64) field64(base, 24) else field32(base, 24)
                versymLink = buf.getInt(base + 40)
            }
        }
    }
    if (dynOff < 0L || dynEnt == 0) return false

    // Virtual address -> file offset via the section that covers the address.
    fun v2f(vaddr: Long): Long {
        for (i in 0 until eShnum) {
            val base = (eShoff + i.toLong() * eShentsize).toInt()
            val type = buf.getInt(base + 4)
            if (type == 8) continue // SHT_NOBITS has no file backing
            val addr = if (is64) field64(base, 16) else field32(base, 16)
            val off = if (is64) field64(base, 24) else field32(base, 24)
            val size = if (is64) field64(base, 32) else field32(base, 32)
            if (addr != 0L && vaddr >= addr && vaddr < addr + size) return off + (vaddr - addr)
        }
        return vaddr
    }

    var modified = false
    // .dynamic entry: d_tag at 0, d_val at 8 (ELF64) or 4 (ELF32).
    val valAt = if (is64) 8 else 4
    val dynEnd = (dynOff + dynSize).toInt()
    var cursor = dynOff.toInt()
    var versymVaddr = -1L
    while (cursor + dynEnt <= dynEnd) {
        val tag = if (is64) buf.getLong(cursor) else buf.getInt(cursor).toLong()
        val value = if (is64) buf.getLong(cursor + valAt) else buf.getInt(cursor + valAt).toLong() and 0xffffffffL
        if (tag == 0L) break
        if (tag == 0x6ffffff0L) versymVaddr = value // DT_VERSYM
        cursor += dynEnt
    }

    if (versymOff >= 0L && versymVaddr >= 0L && versymLink > 0) {
        // .gnu.version is a u16 array parallel to .dynsym; its sh_link points
        // at the dynsym section whose size bounds the array.
        val symBase = (eShoff + versymLink.toLong() * eShentsize).toInt()
        val symSize = if (is64) field64(symBase, 32) else field32(symBase, 32)
        val symEnt = buf.getInt(symBase + 56)
        if (symEnt > 0) {
            val vs = v2f(versymVaddr).toInt()
            var changed = false
            for (j in 0 until (symSize / symEnt).toInt()) {
                val pos = vs + 2 * j
                val v = buf.getShort(pos).toInt() and 0xffff
                if (v >= 2) { // 0 *local* / 1 *global* stay as they are
                    buf.putShort(pos, 1)
                    changed = true
                }
            }
            if (changed) modified = true
        }
    }

    // .gnu.version_r (verneed) is left byte-for-byte intact on purpose: bionic
    // only consults it while resolving a symbol that still carries a version
    // index, and after the rewrite above none do. Gutting it instead breaks
    // the index -> file mapping for any entry that slips through, and dlopen
    // fails with "cannot find verneed/verdef for version index=N".

    if (modified) soFile.writeBytes(data)
    return modified
}

tasks.matching { it.name.matches(Regex("merge\\w*NativeLibs")) }.configureEach {
    doLast {
        outputs.files.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".so") }
                .forEach { soFile ->
                    try {
                        if (patchElfUndSymbols(soFile, api23NativeSymbolRenames, api23NativeSymbolWeakify)) {
                            logger.lifecycle("api23 patch: patched imports in ${soFile.name}")
                        }
                        if (clearElfSymbolVersions(soFile)) {
                            logger.lifecycle("api23 patch: cleared symbol version requirements in ${soFile.name}")
                        }
                    } catch (error: Throwable) {
                        logger.warn("api23 patch: failed on ${soFile.path}: $error")
                    }
                }
        }
    }
}

// ---------------------------------------------------------------------------
// Flutter embedding Java compat patch for Android 6.0 (API 23)
//
// Upstream removed the API 24 guards from the embedding's localization plugin
// when the default minSdk moved to 24; since then FlutterEngine's constructor
// crashes on API 23 with NoSuchMethodError (Configuration.getLocales(), see
// LocalizationPlugin.sendLocalesToFlutter). The vendored copy under
// compat23patch/ restores the guards; the tasks below compile it against the
// exact embedding artifact this build resolves and swap its class files into a
// patched copy of the jar, which then replaces the maven module on every
// classpath. The engine version is read from the Flutter SDK, so a Flutter
// upgrade re-resolves the matching embedding and the patch re-applies; if the
// upstream class surface drifts, refresh the vendored copy from the tag.
// ---------------------------------------------------------------------------

val compat23FlutterSdkDir = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}["flutter.sdk"]?.toString()
    ?: throw GradleException("flutter.sdk is missing from android/local.properties")
val compat23EngineVersion = file("$compat23FlutterSdkDir/bin/internal/engine.version").readText().trim()
val compat23SourceDir = file("compat23patch")
val compat23SdkDir = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}["sdk.dir"]?.toString()
val compat23AndroidJar = run {
    val compileSdkLevel = android.compileSdk ?: throw GradleException("compileSdk is not set")
    val sdkRoot = File(compat23SdkDir ?: "")
    // AGP 9 platform dirs carry a minor version suffix (android-37.0).
    val platformDir = sdkRoot.resolve("platforms").listFiles { f ->
        f.isDirectory && f.name.startsWith("android-$compileSdkLevel")
    }?.minByOrNull { it.name } ?: throw GradleException("platform android-$compileSdkLevel not installed")
    File(platformDir, "android.jar")
}
if (!compat23AndroidJar.isFile) throw GradleException("android.jar not found at $compat23AndroidJar")
val compat23EmbeddingCacheRoot = File(gradle.gradleHomeDir, "caches/modules-2/files-2.1/io.flutter")

// Transitive dependencies declared by the embedding POM, lost when the module
// itself is excluded (the patched jar carries no POM metadata). Versions pinned
// to the 3.47.5 embedding POM; anything higher pulled by other plugins wins.
listOf(
    "androidx.lifecycle:lifecycle-common:2.7.0",
    "androidx.lifecycle:lifecycle-common-java8:2.7.0",
    "androidx.lifecycle:lifecycle-process:2.7.0",
    "androidx.lifecycle:lifecycle-runtime:2.7.0",
    "androidx.fragment:fragment:1.7.1",
    "androidx.annotation:annotation:1.8.1",
    "androidx.tracing:tracing:1.2.0",
    "androidx.core:core:1.13.1",
    "androidx.window:window-java:1.2.0",
    "com.getkeepsafe.relinker:relinker:1.4.5",
    "androidx.exifinterface:exifinterface:1.4.1",
).forEach { dependencies.add("implementation", it) }

listOf("debug", "release").forEach { buildTypeName ->
    val capitalized = buildTypeName.replaceFirstChar { it.uppercaseChar() }

    val patchTask = tasks.register("patchFlutterEmbedding$capitalized") {
        group = "build"
        description = "Rebuilds flutter_embedding_$buildTypeName with the API 23 localization guards restored."
        inputs.dir(compat23SourceDir)
        inputs.property("engineVersion", compat23EngineVersion)
        val outputJar = layout.buildDirectory.file("compat23/flutter_embedding_$buildTypeName-patched.jar")
        outputs.file(outputJar)
        doLast {
            // The embedding jar is already in Gradle's dependency cache from
            // normal builds; fetch it from download.flutter.io only on a cold
            // cache. download.flutter.io serves artifacts without POMs, so a
            // plain configuration resolution cannot be used here.
            val jarName = "flutter_embedding_$buildTypeName-1.0.0-$compat23EngineVersion.jar"
            val originalJar = compat23EmbeddingCacheRoot
                .resolve("flutter_embedding_$buildTypeName")
                .walkTopDown()
                .firstOrNull { it.name == jarName }
                ?: File(temporaryDir, jarName).apply {
                    val url = "https://storage.googleapis.com/download.flutter.io/io/flutter/flutter_embedding_$buildTypeName/1.0.0-$compat23EngineVersion/$jarName"
                    URI(url).toURL().openStream().use { input -> outputStream().use { input.copyTo(it) } }
                }
            val bootJar = compat23AndroidJar

            val classesDir = File(temporaryDir, "classes").apply {
                deleteRecursively()
                mkdirs()
            }
            val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
                ?: throw GradleException("compat23 patch: no system javac; run Gradle on a JDK")
            val javaSources = compat23SourceDir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList()
            if (javaSources.isEmpty()) throw GradleException("compat23 patch: no vendored sources found")
            val arguments = listOf(
                "-classpath",
                listOf(originalJar.absolutePath, bootJar.absolutePath).joinToString(File.pathSeparator),
                "-d", classesDir.absolutePath,
                "-source", "17", "-target", "17",
                "-nowarn", "-proc:none", "-encoding", "UTF-8",
            ) + javaSources.map { it.absolutePath }
            if (compiler.run(null, null, null, *arguments.toTypedArray()) != 0) {
                throw GradleException("compat23 patch: javac failed for $buildTypeName")
            }

            val jarOut = outputJar.get().asFile.apply { parentFile.mkdirs() }
            ZipOutputStream(jarOut.outputStream().buffered()).use { zip ->
                ZipFile(originalJar).use { original ->
                    val entries = original.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val isPatchedClass =
                            entry.name.startsWith("io/flutter/plugin/localization/LocalizationPlugin")
                        val isSignature =
                            entry.name.startsWith("META-INF/") &&
                                (entry.name.endsWith(".SF") || entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA"))
                        if (!entry.isDirectory && !isPatchedClass && !isSignature) {
                            zip.putNextEntry(ZipEntry(entry.name))
                            original.getInputStream(entry).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                classesDir.walkTopDown().filter { it.isFile }.forEach { classFile ->
                    val entryName = classesDir.toPath().relativize(classFile.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    classFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            logger.lifecycle("compat23 patch: flutter_embedding_$buildTypeName patched -> ${jarOut.name}")
        }
    }

    // The patched jar replaces the upstream module everywhere; per-variant
    // implementation scope keeps each build type on its matching embedding.
    configurations.configureEach {
        exclude(group = "io.flutter", module = "flutter_embedding_$buildTypeName")
    }
    dependencies.add("${buildTypeName}Implementation", files(patchTask))
}

