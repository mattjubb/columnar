plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-serial",
            "-Xlint:-processing",
            "--add-modules=jdk.incubator.vector",
        )
    )
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    maxHeapSize = "4g"
    jvmArgs(
        "--add-modules=jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
    )
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--add-modules=jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
    )
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    "testImplementation"(libs.junitJupiter)
    "testImplementation"(libs.jqwik)
    "testImplementation"(libs.assertj)
    "testRuntimeOnly"(libs.junitPlatformLauncher)
}
