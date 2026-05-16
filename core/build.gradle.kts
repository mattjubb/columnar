plugins {
    id("columnar.java-conventions")
}

description = "Schemas, columns, chunks, tables, viewports — the value-level primitives shared by every other module."

dependencies {
    api(libs.fastutil)
    implementation(libs.slf4jApi)
}
