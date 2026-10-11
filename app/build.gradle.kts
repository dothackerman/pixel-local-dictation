import java.io.StringReader
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing (plan D5). tools/set-signing-password.sh writes these keys into
// local.properties (gitignored); one password serves store and key.
val signingProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(StringReader(it)) }
}
val signingStore: String? = signingProps.getProperty("dictation.keystore")
val signingAlias: String? = signingProps.getProperty("dictation.keyAlias")
val signingPassword: String? = signingProps.getProperty("dictation.keystorePassword")
val signingProblem: String? = when {
    signingStore.isNullOrBlank() || signingAlias.isNullOrBlank() || signingPassword.isNullOrBlank() ->
        "local.properties lacks dictation.keystore, dictation.keyAlias or dictation.keystorePassword"
    !file(signingStore).isFile -> "keystore not found at $signingStore"
    else -> null
}

android {
    namespace = "ch.og.dictation"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "ch.og.dictation"
        minSdk = 36
        targetSdk = 36
        versionCode = 11
        versionName = "0.2.4"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            if (signingProblem == null) {
                storeFile = file(signingStore!!)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        // Debuggable so run-as can copy benchmark data; installed only for recording sessions.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += "release"
        }
    }
    testBuildType = "benchmark"
    // Model files are read through AssetManager and must stay uncompressed in the APK.
    androidResources { noCompress += listOf("onnx", "txt") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Release and benchmark builds fail without the key instead of falling back to debug signing.
val checkReleaseSigning by tasks.registering {
    val problem = signingProblem
    doLast {
        if (problem != null) {
            throw GradleException("Release signing not configured: $problem. Run tools/set-signing-password.sh.")
        }
    }
}
tasks.matching { it.name.matches(Regex("pre(Release|Benchmark)(AndroidTest)?Build")) }
    .configureEach { dependsOn(checkReleaseSigning) }

dependencies {
    // Recognizer runtime (ADR-003). tools/fetch-assets.sh provides the AAR and the model assets.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    // Real org.json for laptop unit tests of Variant.parse (the Android stub throws "not mocked").
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
