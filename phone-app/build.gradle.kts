import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val rokid: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun rokidProp(key: String): String = rokid.getProperty(key, "")

android {
    namespace = "dev.wallner.hermesonglass.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wallner.hermesonglass.phone"
        minSdk = 28
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "ROKID_CLIENT_ID", "\"${rokidProp("rokid.clientId")}\"")
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"${rokidProp("rokid.clientSecret")}\"")
        buildConfigField("String", "ROKID_ACCESS_KEY", "\"${rokidProp("rokid.accessKey")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.timber)

    implementation(libs.rokid.cxr.m)

    debugImplementation(libs.java.websocket)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

// Bundle the glasses APK into the phone APK's assets so first-run sideload
// via CxrApi.startUploadApk has a payload to push (D13 / task 4.4).
val bundleGlassesApk by tasks.registering(Copy::class) {
    val glassesAssemble = ":glasses-app:assembleDebug"
    dependsOn(glassesAssemble)
    val source = rootProject.file("glasses-app/build/outputs/apk/debug/glasses-app-debug.apk")
    from(source)
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "glasses-app-release.apk" }
    onlyIf {
        // Skip silently when the glasses APK hasn't been built yet — clean
        // builds run the dependency chain, but a stale source path during
        // partial builds shouldn't fail the phone build.
        source.exists()
    }
}

tasks.named("preBuild") {
    dependsOn(bundleGlassesApk)
}
