// `java.util.Properties` cannot be spelled out inside this script: the Gradle
// `java` extension accessor shadows the package name, and the reference dies as
// `Unresolved reference: util`. Importing the type sidesteps it.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * **No map key is baked into this build, and none should ever be added.**
 *
 * Earlier revisions substituted a `BAIDU_MAP_AK` build property into the
 * manifest so the picker worked out of the box. That was removed deliberately:
 * a key is bound to one package name *and* one signing certificate, so it is
 * useless to anyone who rebuilds this project and a small leak for whoever
 * registered it. It also suppressed the one signal worth having — a key that
 * has quietly stopped working looks exactly like a working one until a request
 * is actually made, so shipping one only moves the failure to a stranger's
 * device with no way for them to fix it.
 *
 * Every provider's key is now entered on the app's settings page and handed to
 * the SDK at runtime; see `mapsdk/MapSdkBootstrap`. The manifest therefore has
 * no `com.baidu.lbsapi.API_KEY` entry for the Baidu SDK to find, which is fine
 * — `SDKInitializer.setApiKey` is the supported path and is what runs.
 */

android {
    namespace = "io.github.coby.fakeloc"
    // 36, not 35. `io.github.libxposed:service` — and the `interface` artifact it
    // pulls in — declares `minCompileSdk 36` in its AAR metadata, and AGP fails
    // the build outright rather than downgrade. The module API genuinely uses
    // newer platform types, so complying is the right move.
    //
    // `targetSdk` deliberately stays at 35: raising compileSdk only widens the
    // API surface we are allowed to reference, it does not opt the app into new
    // runtime behaviour.
    compileSdk = 36
    // Pinned rather than left to the AGP default. Relying on the implicit value
    // once sent the first build off to silently auto-download this package, where
    // it then sat on "still waiting for package manifests" for twenty minutes
    // with the CPU idle.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.coby.fakeloc"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "1.6.0"

        ndk {
            // **arm64-v8a only.** The bundled engine publishes both ARM variants,
            // and the 32-bit set is a flat ~2 MB of APK for devices this app
            // cannot usefully run on anyway: LSPosed needs a rooted phone, and
            // rooting a 32-bit-only device in 2026 is not a thing that happens.
            //
            // This line saved ~14 MB when Baidu and Amap were still bundled; with
            // one renderer left it is a small win, but it costs nothing and the
            // reasoning has not changed.
            //
            // Add `armeabi-v7a` back to this list if that assumption ever stops
            // holding — it is the only line that has to change.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signing material lives outside the repository, read from
    // `keystore.properties` next to this file (gitignored). Two bindings make
    // that separation necessary rather than tidy:
    //
    //  * Android refuses to update a package whose signature differs, so a key
    //    that changes between machines silently orphans every existing install.
    //  * A map key is registered against one package name *and* one
    //    certificate, so the SHA-1 of this key is a value users must type into
    //    a vendor console (the app prints it on its settings page).
    //
    // Absent file => null => debug key. That is deliberate: a fresh clone
    // builds out of the box, it just cannot ship an update for this package.
    val signingProps = rootProject.file("keystore.properties")
    val releaseSigning = if (signingProps.exists()) {
        val props = Properties().apply { signingProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            // **On, and it is the single reason this APK is a few megabytes
            // instead of thirty.**
            //
            // R8 used to be off because the map SDKs resolve their own classes by
            // name — Baidu's auth layer, Amap's GL and JNI entry points,
            // Tencent's engine callbacks registered from `libtxmapengine.so` —
            // and stripping or renaming any of them yields a blank map or an "AK
            // invalid" error indistinguishable from a bad credential, at runtime,
            // on a device only the user has. The answer then was to bundle SDKs
            // that could not be touched.
            //
            // Two things changed on 2026-09-29. Baidu and Amap are gone, so there
            // is one vendor to keep whole rather than three; and measuring the
            // dex showed what the "no shrinking" half of that trade was really
            // costing: of 28 726 classes, 21 224 were androidx/Compose and 1 924
            // Kotlin, retained in full because nothing pruned them, against 391
            // classes of our own code. Keeping `com.tencent.**` and `com.qq.**`
            // verbatim (see proguard-rules.pro) while letting R8 shrink
            // everything else recovers that without touching one SDK class.
            //
            // **The rule to preserve: anything a vendor resolves by name must be
            // kept by name.** That includes the LSPosed entry points, which are
            // named in META-INF/xposed/java_init.list and instantiated by the
            // framework — losing those turns this from a module into a plain app,
            // silently, with nothing on screen to say why.
            isMinifyEnabled = true
            // Off, and it stays off. The app's own `res/` is 31 KB and
            // `resources.arsc` 479 KB — nothing here is worth shrinking — while
            // the vendor SDK is a third party that may look its resources up by
            // name. That is the same reasoning that keeps `com.tencent.**` whole,
            // applied to a resource table instead of a class table.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with this project's own key when `keystore.properties` is
            // present, otherwise with the debug key (see the signingConfigs
            // block above). The two produce **mutually non-upgradable** APKs —
            // signatures must match for `pm install -r` to succeed, so
            // switching keys means uninstalling once.
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // META-INF/xposed/* is the module descriptor read by LSPosed.
            // Without this it can be dropped as a "duplicate resource".
            merges += "META-INF/xposed/*"
        }
        jniLibs {
            // Ship the .so compressed. Baidu's libraries are ~28 MB of the APK
            // and compress to roughly half that; more to the point, unpacking
            // them into the app's lib dir at install time is the loading path
            // every Android release supports, which removes a whole class of
            // UnsatisfiedLinkError on ROMs that mmap them straight out of the
            // APK.
            useLegacyPackaging = true
        }
        // **The single biggest saving in this file, and it costs nothing.**
        //
        // AGP 8 changed the default to `false`, which stores every `classes*.dex`
        // *uncompressed* in the APK so ART can mmap them directly. That is worth
        // something on a large app opened constantly; here it is ~44 MB of dex
        // (three of the map SDKs' own class sets plus Compose) sitting in the
        // download uncompressed, against a saving the user pays for once, on
        // install, in the form of one extra extraction pass.
        //
        // Measured 2026-09-29: dex 44.10 MB stored → ~24 MB deflated, which on
        // its own is the single largest saving available in this file. The
        // README's size table has the before/after.
        //
        // AGP 8.7.3 spells this `packaging.dex.useLegacyPackaging`. There is no
        // top-level `dexUseLegacyPackaging` on the `packaging` block — that
        // property lives on the older `packagingOptions` shape, and using it
        // here fails the script compilation with "Unresolved reference".
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material.icons.extended)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Provided by the framework at runtime — never bundle it.
    compileOnly(libs.libxposed.api)
    // Bundled: lets the manager UI bind to LSPosed and read/write remote prefs.
    implementation(libs.libxposed.service)

    // The one base map the picker can draw. It is compiled in rather than
    // downloaded because the picker is the only way to choose a position, and a
    // renderer that has to be fetched before it can be used is a renderer that is
    // missing exactly when it is needed.
    //
    // **This is the whole map story now.** Baidu (7.4 MB of `.so` plus its search
    // and util modules) and Amap (7.7 MB of `.so` plus ~6 MB of tile assets) were
    // dropped on 2026-09-29 to get the APK under 10 MB, and nothing else in this
    // app needed them: the hooks that forge positions inside a target app's own
    // location SDK reach every vendor's classes by reflection through *that
    // app's* ClassLoader, so the module half links against none of the three.
    // `core/MapSettings.kt` has the long version.
    //
    // Pinned to 5.9.0 by the version catalog; 6.x's artifact is missing
    // `mapsdk.maps.model.LatLng` entirely and cannot be linked. See the note in
    // `gradle/libs.versions.toml`.
    implementation(libs.tencent.map.vector)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.tooling.preview)
}
