plugins {
    id("columnar.java-conventions")
}

description = "Dependency graph, dirty tracker, materialization cache, pull executor, and tick coordinator."

dependencies {
    api(project(":engine"))
    implementation(libs.jctools)
    implementation(libs.fastutil)
    implementation(libs.slf4jApi)
}
