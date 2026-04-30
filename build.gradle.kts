plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "es.iesclaradelrey"
version = "1.4.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.lingala.zip4j:zip4j:2.11.5")
}

intellij {
    version.set("2024.2")
    type.set("IC")
    plugins.set(listOf<String>())
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("")
    }
    buildSearchableOptions {
        enabled = false
    }
    runIde {
        autoReloadPlugins.set(false)
    }
}
