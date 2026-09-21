import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.ed4apk"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")

repositories {
    google()
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val collectDependencyNotices by tasks.registering {
    val runtimeJars = configurations.runtimeClasspath
    inputs.files(runtimeJars)
    val destination = layout.buildDirectory.dir("generated/dependency-notices")
    outputs.dir(destination)
    doLast {
        val root = destination.get().asFile
        delete(root)
        root.mkdirs()
        runtimeJars.get().files.sortedBy { it.name }.forEach { jar ->
            copy {
                from(zipTree(jar)) {
                    include("LICENSE*", "NOTICE*", "COPYING*", "**/LICENSE*", "**/NOTICE*", "**/COPYING*")
                }
                into(root.resolve(jar.nameWithoutExtension))
            }
        }
        root.resolve("dependencies.txt").writeText(
            runtimeJars.get().resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id.toString() }.sorted().joinToString("\n", postfix = "\n")
        )
    }
}

tasks.processResources {
    dependsOn(collectDependencyNotices)
    from(layout.buildDirectory.dir("generated/dependency-notices")) {
        into("META-INF/third-party")
    }
    from("THIRD_PARTY.md") {
        into("META-INF/third-party")
    }
    from("LICENSE") {
        into("META-INF/ed4apk")
    }
}

dependencies {
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.android.tools.smali:smali:3.0.10")
    implementation("com.android.tools.smali:smali-baksmali:3.0.10")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    // 9.3+ requires Java 17; keep the runtime compatible with Java 11.
    implementation("com.android:zipflinger:9.2.1")
    implementation("com.android.tools.build:apksig:9.4.1")
    implementation("com.android.tools:r8:9.4.24")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The build/test toolchain is Java 21; the distributed application targets Java 11 APIs and bytecode.
tasks.compileJava { options.release = 11 }
tasks.compileTestJava { options.release = 21 }

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.shadowJar)
    systemProperty("ed4apk.jar", layout.buildDirectory.file("libs/ed4apk.jar").get().asFile.absolutePath)
    providers.gradleProperty("ed4apkTestJava").orNull?.let { systemProperty("ed4apk.java", it) }
}

tasks.shadowJar {
    archiveFileName = "ed4apk.jar"
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    manifest.attributes["Main-Class"] = "dev.ed4apk.Main"
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

// Prevent a future library update from silently raising the minimum runtime again.
val verifyJava11Bytecode by tasks.registering {
    dependsOn(tasks.shadowJar)
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(fatJar)
    doLast {
        ZipFile(fatJar.get().asFile).use { zip ->
            val incompatible = zip.entries().asSequence()
                .filter { entry ->
                    entry.name.endsWith(".class") && (!entry.name.startsWith("META-INF/versions/") ||
                        entry.name.split('/')[2].toIntOrNull()?.let { it <= 11 } == true)
                }
                .mapNotNull { entry ->
                    zip.getInputStream(entry).use { stream ->
                        val header = DataInputStream(stream)
                        check(header.readInt() == 0xcafebabe.toInt()) { "Invalid class: ${entry.name}" }
                        header.readUnsignedShort()
                        val major = header.readUnsignedShort()
                        if (major > 55) "${entry.name}: class version $major" else null
                    }
                }.toList()
            check(incompatible.isEmpty()) { "Fat JAR requires newer than Java 11:\n${incompatible.joinToString("\n")}" }
        }
    }
}

tasks.check { dependsOn(verifyJava11Bytecode) }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Publish only the self-contained JAR, not a second JAR that needs a classpath.
tasks.jar {
    enabled = false
}

tasks.register<Copy>("copyRuntimeDependencies") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("dependencies"))
}
