pluginManagement {
    includeBuild("kotlin-tools/base-plugins")
}
plugins {
    id("net.rubygrapefruit.kotlin-base")
}

includeBuild("kotlin-tools/base-libs")
includeBuild("kotlin-tools/libs")

rootProject.name = "sync"
