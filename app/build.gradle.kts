plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Where the app looks for the published subscription (booksources.json, app-version.json).
// Override with -PsubscriptionRepo=owner/repo -PsubscriptionBranch=main if the repository moves.
val subscriptionRepo = (project.findProperty("subscriptionRepo") as String?) ?: "read0001/read"
val subscriptionBranch = (project.findProperty("subscriptionBranch") as String?) ?: "main"

android {
    namespace = "app.openread"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.openread"
        minSdk = 26
        targetSdk = 35
        // Override with -PappVersionCode=… -PappVersionName=… (the update flow is tested with a newer build).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.8"
        buildConfigField("String", "SUBSCRIPTION_REPO", "\"$subscriptionRepo\"")
        buildConfigField("String", "SUBSCRIPTION_BRANCH", "\"$subscriptionBranch\"")
    }

    signingConfigs {
        create("release") {
            // CI and local release builds pass the keystore through environment variables; without them the
            // release build falls back to the debug key so it can still be installed for testing.
            val path = System.getenv("OPENREAD_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("OPENREAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENREAD_KEY_ALIAS")
                keyPassword = System.getenv("OPENREAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("OPENREAD_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/io.netty.versions.properties", "mozilla/public-suffix-list.txt",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
}
