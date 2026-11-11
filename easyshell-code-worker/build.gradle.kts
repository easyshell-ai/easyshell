plugins { java; application }

repositories { mavenCentral() }

dependencies {
    implementation("org.graalvm.polyglot:polyglot:25.2.4")
    runtimeOnly("org.graalvm.polyglot:js-community:25.2.4")
    runtimeOnly("org.graalvm.polyglot:python-community:25.2.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
application { mainClass.set("com.easyshell.codeworker.Worker") }
tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    maxHeapSize = "512m"
    // Replace the forked test JVM environment; never forward the Gradle host environment.
    setEnvironment(mapOf(
        "EASYSHELL_PROBE_MARKER" to "synthetic-environment-marker-25-2-4",
        "GRAAL_PYTHONHOME" to "/nonexistent-easyshell-synthetic-python-home-25-2-4"
    ))
}

val prepareResources by tasks.registering(JavaExec::class) {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easyshell.codeworker.PrepareResources")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "256m"
    systemProperty("truffle.UseFallbackRuntime", "true")
    setEnvironment(emptyMap<String, String>())
}

tasks.register<JavaExec>("exportResources") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easyshell.codeworker.ExportResources")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "256m"
    args(layout.buildDirectory.dir("isolated-resources").get().asFile.absolutePath)
    systemProperty("truffle.UseFallbackRuntime", "true")
    setEnvironment(emptyMap<String, String>())
    // Intentionally no overwrite/up-to-date shortcut: existing exports require explicit review.
}

tasks.register<JavaExec>("verifyPreparedResources") {
    dependsOn(prepareResources)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easyshell.codeworker.PythonDiagnosticWorker")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "256m"
    systemProperty("user.home", layout.buildDirectory.dir("diagnostic-home").get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", "/tmp")
    systemProperty("truffle.UseFallbackRuntime", "true")
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")
    systemProperty("polyglot.engine.resourcePath", layout.buildDirectory.dir("graalvm-resources").get().asFile.absolutePath)
    setEnvironment(emptyMap<String, String>())
}
