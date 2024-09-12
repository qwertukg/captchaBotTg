plugins {
    kotlin("jvm") version "2.0.10"
}

group = "kz.qwertukg"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.2.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("org.openpnp:opencv:4.9.0-0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}

// Задача для создания "fat JAR"
tasks.register<Jar>("buildFatJar") {
    group = "build"
    manifest {
        attributes["Main-Class"] = "kz.qwertukg.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("${project.name}-${project.version}-all.jar")
}