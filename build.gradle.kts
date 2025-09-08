import java.util.Properties

plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.3"
}

group = "com.anvilsunlocked"

// Derive version from version.properties: Minecraft_Plugin (e.g., 1.21.8_1.6.0.rc1)
val versionProps = Properties().apply {
    val vf = rootProject.file("version.properties")
    if (vf.exists()) vf.inputStream().use { load(it) }
}
fun String?.normalizeRc(): String? = this?.replace("rc.", "rc")
val mcVer = versionProps.getProperty("minecraft") ?: "1.21.1"
val major = (versionProps.getProperty("plugin_major") ?: "0").toInt()
val minor = (versionProps.getProperty("plugin_minor") ?: "0").toInt()
val patch = (versionProps.getProperty("plugin_patch") ?: "0").toInt()
val candRaw = versionProps.getProperty("plugin_candidate").normalizeRc()
val pluginVer = if (!candRaw.isNullOrBlank()) "$major.$minor.$patch.$candRaw" else "$major.$minor.$patch"
version = "${mcVer}_${pluginVer}"

java {
    // Prefer JDK 21; if not available, Gradle 8.8 may still compile with current JVM
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("AnvilsUnlocked")
        archiveClassifier.set("")
    }

    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/AnvilsUnlocked-${project.version}.jar"))
    }

    assemble {
        dependsOn(reobfJar)
    }
}
