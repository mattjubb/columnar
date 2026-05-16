plugins {
    id("columnar.java-conventions")
}

description = "Tiered on-heap (HOT) and off-heap (WARM, MemorySegment) chunk residency with LRU eviction."

dependencies {
    api(project(":core"))
    implementation(libs.slf4jApi)
}
