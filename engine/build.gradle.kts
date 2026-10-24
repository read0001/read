plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.okhttp)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.jsoup)
    api(libs.jspecify)
    api(libs.rhino)
    implementation(libs.jsonpath)
    implementation(libs.jsoupxpath)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
