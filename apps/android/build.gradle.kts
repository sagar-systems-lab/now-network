import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.androidx.baselineprofile)
}

val hostedRuntime = providers.gradleProperty("NOW_RUNTIME")
    .orElse(providers.environmentVariable("NOW_RUNTIME"))
    .getOrElse("offline") == "hosted"
val hostedPublicConfig = Properties().apply {
    if (hostedRuntime) file("hosted-runtime.properties").inputStream().use { load(it) }
}

fun publicConfig(name: String, fallback: String = ""): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(hostedPublicConfig.getProperty(name, fallback))
        .get()

if (hostedRuntime) {
    listOf("NOW_API_BASE_URL", "NOW_SUPABASE_URL", "NOW_SUPABASE_PUBLISHABLE_KEY").forEach { name ->
        require(publicConfig(name).isNotBlank()) { "Missing public Android runtime value: $name" }
    }
    require(publicConfig("NOW_API_BASE_URL").startsWith("https://")) { "Hosted API requires HTTPS" }
    require(publicConfig("NOW_SUPABASE_URL").startsWith("https://")) { "Hosted auth requires HTTPS" }
    require(publicConfig("NOW_SUPABASE_PUBLISHABLE_KEY").startsWith("sb_publishable_")) {
        "Hosted Android requires a public client key"
    }
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""


android {
    namespace = "com.sagarsystemslab.nownetwork"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sagarsystemslab.nownetwork"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        listOf("FIREBASE_APP_ID", "FIREBASE_PROJECT_ID", "FIREBASE_API_KEY", "FIREBASE_SENDER_ID").forEach { name ->
            buildConfigField("String", name, buildConfigString(publicConfig("NOW_$name")))
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "API_BASE_URL",
            buildConfigString(publicConfig("NOW_API_BASE_URL")),
        )
        buildConfigField(
            "String",
            "SUPABASE_URL",
            buildConfigString(publicConfig("NOW_SUPABASE_URL")),
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(publicConfig("NOW_SUPABASE_PUBLISHABLE_KEY")),
        )
        buildConfigField(
            "String",
            "SOLANA_CLUSTER",
            buildConfigString(publicConfig("NOW_SOLANA_CLUSTER", "devnet")),
        )
        buildConfigField(
            "String",
            "SOLANA_RPC_URL",
            buildConfigString(
                publicConfig("NOW_SOLANA_RPC_URL", "https://api.devnet.solana.com"),
            ),
        )
        buildConfigField(
            "String",
            "SOLANA_PROGRAM_ID",
            buildConfigString(
                publicConfig(
                    "NOW_SOLANA_PROGRAM_ID",
                    "sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T",
                ),
            ),
        )
        buildConfigField(
            "String",
            "WALLET_IDENTITY_URI",
            buildConfigString(
                publicConfig(
                    "NOW_WALLET_IDENTITY_URI",
                    "https://github.com/sagar-systems-lab/now-network",
                ),
            ),
        )
        buildConfigField(
            "String",
            "WALLET_ICON_URI",
            buildConfigString(publicConfig("NOW_WALLET_ICON_URI", "favicon.ico")),
        )
        buildConfigField(
            "String",
            "BROWSE_AREA_LABEL",
            buildConfigString(publicConfig("NOW_BROWSE_AREA_LABEL")),
        )
        buildConfigField(
            "String",
            "BROWSE_LATITUDE",
            buildConfigString(publicConfig("NOW_BROWSE_LATITUDE")),
        )
        buildConfigField(
            "String",
            "BROWSE_LONGITUDE",
            buildConfigString(publicConfig("NOW_BROWSE_LONGITUDE")),
        )
        buildConfigField(
            "String",
            "BROWSE_RADIUS_METERS",
            buildConfigString(publicConfig("NOW_BROWSE_RADIUS_METERS", "3000")),
        )
        buildConfigField(
            "String",
            "REWARD_MINT",
            buildConfigString(publicConfig("NOW_REWARD_MINT")),
        )
        buildConfigField(
            "String",
            "REWARD_SYMBOL",
            buildConfigString(publicConfig("NOW_REWARD_SYMBOL", "USDC")),
        )
        buildConfigField(
            "String",
            "REWARD_DECIMALS",
            buildConfigString(publicConfig("NOW_REWARD_DECIMALS", "6")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}",
    )
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation(project(":core:designsystem"))
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.realtime)

    implementation(libs.hilt.android)
    implementation(libs.mobile.wallet.adapter)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    baselineProfile(project(":benchmark"))
}
