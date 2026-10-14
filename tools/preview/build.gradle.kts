// Desktop-JVM build of the game sources, for running the unit tests and exporting
// scene previews without the Android SDK. Android framework classes come from
// Robolectric's android-all jar on Maven Central.
plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }

val app = file("../../app/src")
val androidAll = "org.robolectric:android-all:15-robolectric-13954326"

sourceSets {
    main {
        // Everything compiles here, the renderer and activity included (against android-all), so
        // they are type-checked locally too.
        kotlin.srcDir(app.resolve("main/java"))
    }
    test { kotlin.srcDir(app.resolve("test/java")) }
}
val preview by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output
}
dependencies {
    compileOnly(androidAll)
    "previewImplementation"(androidAll)
    testImplementation("junit:junit:4.13.2")
}

tasks.register<JavaExec>("export") {
    classpath = preview.runtimeClasspath
    mainClass.set("ExportKt")
    args(project.findProperty("out")?.toString() ?: "build/export")
}

tasks.register<JavaExec>("sound") {
    classpath = preview.runtimeClasspath
    mainClass.set("SoundExportKt")
    args(project.findProperty("out")?.toString() ?: "build/sound")
}
