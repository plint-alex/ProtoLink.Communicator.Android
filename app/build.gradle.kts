import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("dagger.hilt.android.plugin")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "ru.protolink.communicator"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.protolink.communicator"
        minSdk = 28
        targetSdk = 34
        versionCode = 22
        versionName = "1.0.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("PROTOLINK_API_BASE_URL") ?: "http://protolink.ru/"}\"")
    }

    // Release signingConfig only when keystore.properties exists so Debug CI
    // (assembleDebug / testDebugUnitTest) configures without secrets.
    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile")
                    ?: error("keystore.properties missing storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: error("keystore.properties missing storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: error("keystore.properties missing keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: error("keystore.properties missing keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.4" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":sync"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.9.0")

    implementation("com.microsoft.signalr:signalr:8.0.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** Full HEAD sha, or "unknown" when git is unavailable. */
fun gitHeadSha(repoDir: File): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && text.matches(Regex("[0-9a-f]{40}"))) text else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

val appExt = extensions.getByType(AppExtension::class.java)
val releaseVersionName = appExt.defaultConfig.versionName
    ?: error("defaultConfig.versionName is required")
val releaseVersionCode = appExt.defaultConfig.versionCode
    ?: error("defaultConfig.versionCode is required")
val versionedApkName = "ProtoLink.Communicator.Android-$releaseVersionName.apk"

// Register during configuration (before AGP finalizes outputFileName). afterEvaluate is too late on AGP 8.2.
appExt.applicationVariants.configureEach {
    if (buildType.name != "release") return@configureEach
    outputs.configureEach {
        (this as BaseVariantOutputImpl).outputFileName = versionedApkName
    }
}

// afterEvaluate: assembleRelease is a lifecycle task created by AGP; configureEach-before-creation can miss doLast.
afterEvaluate {
    tasks.named("assembleRelease").configure {
        doFirst {
            check(keystorePropertiesFile.isFile) {
                "Release signing requires gitignored keystore.properties (see README). Keystore must live outside git."
            }
        }
        doLast {
            val builtApk = layout.buildDirectory.file("outputs/apk/release/$versionedApkName").get().asFile
            if (!builtApk.isFile) {
                error("Expected Gradle release APK not found (must already be version-named): $builtApk")
            }

            // AGP minSdk≥24 may omit usable v1 for OEM scanners; force v1+v2+v3 via apksigner.
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.isFile) f.inputStream().use { load(it) }
            }
            val sdkDir = localProps.getProperty("sdk.dir")?.replace("\\:", ":")
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: error("Release resign needs sdk.dir in local.properties or ANDROID_HOME")
            val buildTools = file(sdkDir).resolve("build-tools").listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: error("No Android build-tools under $sdkDir/build-tools")
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val apksigner = File(buildTools, if (isWin) "apksigner.bat" else "apksigner")
            check(apksigner.isFile) { "apksigner not found: $apksigner" }

            val storeFile = file(
                keystoreProperties.getProperty("storeFile")
                    ?: error("keystore.properties missing storeFile"),
            )
            val storePassword = keystoreProperties.getProperty("storePassword")
                ?: error("keystore.properties missing storePassword")
            val keyAlias = keystoreProperties.getProperty("keyAlias")
                ?: error("keystore.properties missing keyAlias")
            val keyPassword = keystoreProperties.getProperty("keyPassword")
                ?: error("keystore.properties missing keyPassword")
            val tmpOut = File(builtApk.parentFile, "${builtApk.name}.resigning.apk")
            if (tmpOut.exists()) tmpOut.delete()
            val envKs = "PROTOLINK_KS_PASS"
            val envKey = "PROTOLINK_KEY_PASS"
            val sign = ProcessBuilder(
                apksigner.absolutePath,
                "sign",
                "--ks", storeFile.absolutePath,
                "--ks-key-alias", keyAlias,
                "--ks-pass", "env:$envKs",
                "--key-pass", "env:$envKey",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--min-sdk-version", "19",
                "--out", tmpOut.absolutePath,
                builtApk.absolutePath,
            ).apply {
                environment()[envKs] = storePassword
                environment()[envKey] = keyPassword
                redirectErrorStream(true)
            }.start()
            val signLog = sign.inputStream.bufferedReader().readText()
            check(sign.waitFor() == 0) { "apksigner sign failed:\n$signLog" }
            tmpOut.copyTo(builtApk, overwrite = true)
            tmpOut.delete()

            val verify = ProcessBuilder(
                apksigner.absolutePath,
                "verify",
                "--verbose",
                "--min-sdk-version", "19",
                builtApk.absolutePath,
            ).redirectErrorStream(true).start()
            val verifyLog = verify.inputStream.bufferedReader().readText()
            check(verify.waitFor() == 0) { "apksigner verify failed:\n$verifyLog" }
            check(verifyLog.contains("Verified using v1 scheme (JAR signing): true")) {
                "Expected v1 signing after resign:\n$verifyLog"
            }
            check(verifyLog.contains("Verified using v2 scheme (APK Signature Scheme v2): true")) {
                "Expected v2 signing after resign:\n$verifyLog"
            }
            check(verifyLog.contains("Verified using v3 scheme (APK Signature Scheme v3): true")) {
                "Expected v3 signing after resign:\n$verifyLog"
            }
            println(verifyLog.lineSequence().take(8).joinToString("\n"))

            val artifactsDir = rootProject.layout.projectDirectory.dir("artifacts").asFile
            artifactsDir.mkdirs()
            val destApk = File(artifactsDir, versionedApkName)
            builtApk.copyTo(destApk, overwrite = true)

            val commit = gitHeadSha(rootProject.projectDir)
            val buildDate = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val releaseJson = buildString {
                append('{')
                append("\"component\":\"ProtoLink.Communicator.Android\",")
                append("\"version\":\"$releaseVersionName\",")
                append("\"versionCode\":$releaseVersionCode,")
                append("\"commit\":\"$commit\",")
                append("\"buildDate\":\"$buildDate\"")
                append('}')
            }
            File(artifactsDir, "$versionedApkName.release.json").writeText(releaseJson)
            println("Release APK: $destApk")
            println("Release meta: ${destApk.name}.release.json")
        }
    }
}
