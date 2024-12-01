plugins {
    id("net.rubygrapefruit.jvm.cli-app")
    kotlin("plugin.serialization")
}

application {
    targetJavaVersion = 21

    dependencies {
        implementation("net.rubygrapefruit.libs:strings:1.0")
        implementation("net.rubygrapefruit.libs:cli-app:1.0")
        implementation("net.rubygrapefruit.libs:file-io:1.0")
        implementation("net.rubygrapefruit.libs:store:1.0")
    }
}
