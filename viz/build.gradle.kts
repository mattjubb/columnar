plugins {
    id("columnar.java-conventions")
    application
}

description =
    "Browser AG Grid viewer via Server-Sent Events; ServiceLoader TableVisualizer + demos."

dependencies {
    api(project(":core"))
    implementation(libs.slf4jSimple)
    implementation(libs.vertxWeb)
}

application {
    mainClass.set("io.columnar.viz.DemoInterestRateDv01Pivot")
}

tasks.withType<JavaCompile>().configureEach {
    /*
     * vertx-core embeds codegen metadata (GenIgnore) that javac's classfile lint cannot interpret;
     * otherwise every compile prints noisy warnings when reading upstream JAR bytecode.
     */
    options.compilerArgs.add("-Xlint:-classfile")
}

tasks.named<JavaExec>("run").configure {
    maxHeapSize = "3g"
    description =
        "IR DV01 demo: 100k wide trades, server-side infinite row blocks, ticking all DV01 cells."
}
