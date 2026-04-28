plugins {
    id("zenithproxy.plugin.dev") version "1.0.0-SNAPSHOT"
}

group = properties["maven_group"] as String
version = properties["plugin_version"] as String
val mc = properties["mc"] as String
val pluginId = properties["plugin_id"] as String

// Run the build on JDK 25 (matching CI), but emit a Java 21-compatible plugin
// via zenithProxyPlugin.javaReleaseVersion below.
java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

zenithProxyPlugin {
    templateProperties = mapOf(
        "version"      to project.version,
        "mc_version"   to mc,
        "plugin_id"    to pluginId,
        "maven_group"  to group as String,
    )
    javaReleaseVersion = JavaLanguageVersion.of(21)
}

repositories {
    maven("https://maven.2b2t.vc/releases") { description = "ZenithProxy Releases" }
    maven("https://maven.2b2t.vc/remote")   { description = "Dependencies used by ZenithProxy" }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")

    // Test dependencies only — main code uses only ZenithProxy-bundled libs (Gson, SLF4J, etc.)
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("com.google.code.gson:gson:2.14.0")
}

tasks.withType<Jar>().configureEach {
    // Keep the project license attached to every distributed jar, including the release artifact.
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.test {
    useJUnitPlatform()
}
