plugins {
    id("columnar.java-conventions")
    id("me.champeau.jmh") version "0.7.2"
}

description = "JMH benchmarks for column kernels, operators, and end-to-end pull throughput."

dependencies {
    implementation(project(":api"))
    implementation(libs.jmhCore)
    annotationProcessor(libs.jmhAnnotations)
}

jmh {
    jvmArgs.set(listOf("--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED"))
    fork.set(1)
    warmupIterations.set(2)
    iterations.set(3)
}
