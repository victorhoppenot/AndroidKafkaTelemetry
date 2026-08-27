plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val envFile = rootProject.layout.projectDirectory.file(".env")

val envVars: Map<String, String> = (providers.fileContents(envFile).asText.orNull
    ?: throw GradleException("Missing ${envFile.asFile}; copy .env.example to .env and fill it in."))
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null
        else line.take(separator).trim() to line.substring(separator + 1).trim().trim('"')
    }
    .toMap()

fun requireEnv(key: String): String = envVars[key]?.takeIf { it.isNotEmpty() }
    ?: throw GradleException("$key is not set in ${envFile.asFile}; see .env.example.")

val bridgeHostFromEnv = requireEnv("BRIDGE_HOST")
abstract class GenerateNetworkSecurityConfig : DefaultTask() {

    @get:Input
    abstract val bridgeHost: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.get().file("xml/network_security_config.xml").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Generated from .env by :app:generateNetworkSecurityConfig. Do not edit. -->
            <network-security-config>
                <!-- device-bridge is only reachable over the tailnet; the tunnel already
                     encrypts this, so plain ws:// to that one host is fine. -->
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="false">${bridgeHost.get()}</domain>
                </domain-config>
            </network-security-config>

            """.trimIndent()
        )
    }
}

val generateNetworkSecurityConfig =
    tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
        bridgeHost.set(bridgeHostFromEnv)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateNetworkSecurityConfig,
            GenerateNetworkSecurityConfig::outputDir,
        )
    }
}

android {
    namespace = "com.example.vnetgps"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vnetgps"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "NATS_SERVER_URL",
            "\"nats://${requireEnv("NATS_HOST")}:${requireEnv("NATS_PORT")}\"",
        )
        buildConfigField(
            "String",
            "BRIDGE_BASE_URL",
            "\"ws://$bridgeHostFromEnv:${requireEnv("BRIDGE_PORT")}\"",
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.location)
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")

    implementation("io.nats:jnats:2.21.1")
}
