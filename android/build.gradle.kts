import com.android.build.gradle.BaseExtension
import java.util.Properties
import java.io.File

// The 16 KB page-size fplayer rebuild is not published to any remote repository:
// it ships inside the flv_lzc package, under its own android/libs Maven layout.
// Gradle resolves the *app's* runtime classpath against the repositories of the
// project that owns the configuration, so the plugin's own `repositories {}`
// block cannot help here — the app has to declare that repository itself. The
// path is read from the plugin project instead of a copy inside this repository,
// so a version bump in the package is picked up automatically.
val flvLibs = rootProject.project(":flv_lzc").projectDir.resolve("libs")

allprojects {
    repositories {
        maven(flvLibs) {
            content {
                includeModule("io.github.flutterplayer", "fplayer-core")
            }
        }
        google()
        mavenCentral()
    }
}

val pubspecVersionCode: String by lazy {
    try {
        val pubspecFile = rootProject.file("../pubspec.yaml")
        if (pubspecFile.exists()) {
            val versionLine = pubspecFile.readLines().find { it.trim().startsWith("version:") }
            versionLine?.substringAfterLast("+")?.trim() ?: "1"
        } else {
            "1"
        }
    } catch (e: Exception) {
        "1"
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Android 6.0 (API 23) floor. Mirrors android6MinSdk in app/build.gradle.kts —
// the app pins its own copy so Flutter's build-time minSdk migrator cannot
// rewrite it, and this one is applied to the plugin subprojects below.
val android6MinSdk = 23

val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("../../build").get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    afterEvaluate {
        if (project.name != "app") {
            extensions.findByType(com.android.build.gradle.BaseExtension::class.java)?.apply {
                compileSdkVersion(37)
                if (namespace.isNullOrBlank()) {
                    namespace = project.group.toString()
                }
                // Current-generation Flutter plugins (better_player_plus,
                // permission_handler_android, shared_preferences_android,
                // url_launcher_android, video_player_android, …) pin minSdk 24 to
                // match Flutter 3.47's default floor. This TV build still ships
                // to Android 6.0 (API 23) boxes, so pull every plugin back down
                // to the app's minSdk or the manifest merger rejects the app.
                // The plugins gate their post-23 code paths behind
                // Build.VERSION checks; better_player_plus' engine-attach and
                // PIP paths were verified to be guarded.
                if ((defaultConfig.minSdk ?: 21) > android6MinSdk) {
                    defaultConfig.minSdk = android6MinSdk
                }
            }
        }
    }
}

subprojects {
    if (project.name != "app") {
        evaluationDependsOn(":app")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
