plugins {
    id("columnar.java-conventions")
}

description = "Physical operators: Filter, Project, HashAggregate, HashJoin, Pivot, OrderBy. Each is viewport-aware and pull-based."

dependencies {
    api(project(":core"))
    api(project(":expr"))
    implementation(project(":memory"))
    implementation(libs.fastutil)
    implementation(libs.slf4jApi)
}
