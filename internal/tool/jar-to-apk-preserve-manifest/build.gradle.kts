plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.application)
}

dependencies {
    implementation(project(":common"))
}

application {
    mainClass.set("com.squareup.stoic.apk.MainKt")
}
