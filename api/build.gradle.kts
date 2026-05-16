plugins {
    id("columnar.java-conventions")
}

description = "Fluent user-facing API: TableContext, Table builders/factories, table operations, viewport subscriptions."

dependencies {
    api(project(":core"))
    api(project(":query"))
    api(project(":engine"))
    implementation(project(":expr"))
    implementation(libs.slf4jApi)
}
