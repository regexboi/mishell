import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use(::load)
    }
}

fun quoteForBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val sttApiKey = (localProperties.getProperty("STT_API_KEY")
    ?: System.getenv("STT_API_KEY")
    ?: "").trim()
val llmStreamUrl = (localProperties.getProperty("LLM_STREAM_URL")
    ?: System.getenv("LLM_STREAM_URL")
    ?: "https://mishell.mishcaslab.com/v1/llm/stream").trim()

android {
    namespace = "ai.mishell.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.mishell.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "STT_API_KEY", quoteForBuildConfig(sttApiKey))
        buildConfigField("String", "LLM_STREAM_URL", quoteForBuildConfig(llmStreamUrl))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
