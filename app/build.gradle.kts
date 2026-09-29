import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Release signing comes from keystore.properties (git-ignored) or LANBEAM_* environment variables
// (CI). Without either, release builds are left unsigned instead of failing.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.example.lanbeam"
    compileSdk = 36
    defaultConfig {
        // Play rejects com.example.* ids. This is the permanent store identity: never change it
        // after the first upload. (The Kotlin namespace below can stay as is.)
        applicationId = "io.github.m1d0r1x.lanbeam"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "2.4"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "LANBEAM_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "LANBEAM_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "LANBEAM_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "LANBEAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      // The server tests run the real NanoHTTPD stack on the JVM; android.* calls that are not
      // on that path (MimeTypeMap, Environment) just return defaults instead of throwing.
      unitTests.isReturnDefaultValues = true
      unitTests.all {
        it.systemProperty("lanbeam.bench", System.getProperty("lanbeam.bench") ?: "")
        it.systemProperty("lanbeam.serve", System.getProperty("lanbeam.serve") ?: "")
        it.maxHeapSize = "1g"
        it.testLogging { showStandardStreams = true }
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Embeddable HTTP Server and QR Code generator
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
  implementation("com.google.zxing:core:3.5.3")
  implementation("androidx.compose.material:material-icons-core")
}
