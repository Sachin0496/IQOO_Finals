// Mouna's decision core: pure Kotlin (no Android), a port of harness/mouna_harness/core.py.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    compileOnly("org.json:json:20240303") // provided by Android itself; on the JVM only for tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// The Lab's data is the single source: Ask tree, blocks and phrase pack are packed under /mouna/.
tasks.processResources {
    from(rootProject.file("../lab/src/core")) {
        include("ask-tree.json", "blocks.json", "phrase-pack.json")
        into("mouna")
    }
}

tasks.test {
    systemProperty("mouna.vectors", rootProject.file("../harness/vectors/core.json").absolutePath)
    systemProperty("mouna.freetalk", rootProject.file("../harness/vectors/freetalk.json").absolutePath)
}
