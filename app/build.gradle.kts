import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noxsip.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noxsip.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 59
        versionName = "1.6.35"

        // APK ছোট করতে শুধু আধুনিক ARM ফোনের লাইব্রেরি রাখা (x86 / x86_64 বাদ)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release সাইনিং - keystore.properties থেকে পড়া হয় (গিটে কমিট করবেন না)
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Update feed URL comes from the git-ignored local.properties (key: updateUrl), so a private server
    // address never lands in the public source. Empty = update check disabled.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val updateUrl = localProps.getProperty("updateUrl", "").trim()

    // Lite: Beta-র কপি থেকে আলাদা প্রজেক্ট। GitHub বা VPS-এ যায় না, হাতে ইনস্টল।
    //  com.noxsip.app.lite, SMS ও সেলফ-আপডেট ছাড়া, VERSION_URL ফাঁকা (আপডেট চেক বন্ধ)
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionCode = 6
            versionName = "1.0.6"
            buildConfigField("String", "VERSION_URL", "\"$updateUrl\"")
        }
    }

    // Optional private build extras: if app/src/private/kotlin exists (git-ignored) it is compiled in,
    // otherwise the public no-op stub in app/src/publicstub/kotlin is used.
    sourceSets.getByName("main").java.srcDir(
        if (file("src/private/kotlin").exists()) "src/private/kotlin" else "src/publicstub/kotlin"
    )

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            isShrinkResources = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // LinPhone SIP SDK
    implementation(libs.linphone.sdk)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
}
