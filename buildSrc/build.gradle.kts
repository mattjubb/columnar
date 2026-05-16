plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Workaround so that precompiled script plugins (under src/main/kotlin) can use
// the `libs` version-catalog accessors via `the<LibrariesForLibs>()`.
// See: https://github.com/gradle/gradle/issues/15383
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
