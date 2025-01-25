plugins {
    id("net.rubygrapefruit.jvm.cli-app")
    kotlin("plugin.serialization")
}

application {
    targetJavaVersion = 21

    dependencies {
        implementation("net.rubygrapefruit:basics:1.0")
        implementation("net.rubygrapefruit:cli-app:1.0")
        implementation("net.rubygrapefruit:file-io:1.0")
        implementation("net.rubygrapefruit:store:1.0")
    }
}
