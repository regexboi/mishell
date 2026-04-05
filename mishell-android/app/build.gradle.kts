import java.util.Properties
import java.io.File

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

fun readDotEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return buildMap {
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return@forEachLine
            val key = line.substring(0, separatorIndex).trim()
            if (key.isEmpty()) return@forEachLine
            var value = line.substring(separatorIndex + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                value = value.substring(1, value.length - 1)
            }
            put(key, value)
        }
    }
}

val dotEnv = readDotEnv(rootProject.file(".env"))

val mishellApiKey = (localProperties.getProperty("MISHELL_API_KEY")
    ?: System.getenv("MISHELL_API_KEY")
    ?: dotEnv["MISHELL_API_KEY"]
    ?: "").trim()
val groqApiKey = (localProperties.getProperty("GROQ_API_KEY")
    ?: System.getenv("GROQ_API_KEY")
    ?: dotEnv["GROQ_API_KEY"]
    ?: "").trim()
val llmStreamUrl = (localProperties.getProperty("LLM_STREAM_URL")
    ?: System.getenv("LLM_STREAM_URL")
    ?: dotEnv["LLM_STREAM_URL"]
    ?: "https://mini.li-daggertooth.ts.net/mishell-mcp/v1/llm/stream").trim()
val clawdiaGatewayUrl = (localProperties.getProperty("CLAWDIA_GATEWAY_URL")
    ?: System.getenv("CLAWDIA_GATEWAY_URL")
    ?: dotEnv["CLAWDIA_GATEWAY_URL"]
    ?: "https://mini.li-daggertooth.ts.net/openclaw").trim()
val neonString = (localProperties.getProperty("NEON_STRING")
    ?: System.getenv("NEON_STRING")
    ?: dotEnv["NEON_STRING"]
    ?: "").trim()

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

        buildConfigField("String", "MISHELL_API_KEY", quoteForBuildConfig(mishellApiKey))
        buildConfigField("String", "GROQ_API_KEY", quoteForBuildConfig(groqApiKey))
        buildConfigField("String", "LLM_STREAM_URL", quoteForBuildConfig(llmStreamUrl))
        buildConfigField("String", "CLAWDIA_GATEWAY_URL", quoteForBuildConfig(clawdiaGatewayUrl))
        buildConfigField("String", "NEON_STRING", quoteForBuildConfig(neonString))
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
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.postgresql:postgresql:42.2.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
