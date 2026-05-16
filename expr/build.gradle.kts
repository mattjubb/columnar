plugins {
    id("columnar.java-conventions")
}

description = "Expression AST, interpreter (oracle), and ByteBuddy codegen for vector predicates / projectors / aggregate kernels."

dependencies {
    api(project(":core"))
    implementation(project(":memory"))
    implementation(libs.byteBuddy)
    implementation(libs.fastutil)
    implementation(libs.slf4jApi)
}
