plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
kotlin { jvmToolchain(21) }
