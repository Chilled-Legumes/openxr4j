plugins {
    `java-library`
}

group = "me.corriekay"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
}

val lwjglVersion = "3.4.3"

// LWJGL ships its native code in per-platform jars. Pick the one for the
// machine doing the build.
val lwjglNatives = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "natives-windows"
        os.contains("mac") -> "natives-macos"
        else -> "natives-linux"
    }
}

dependencies {
    // LWJGL is an implementation detail. Nothing from it appears in openxr4j's
    // public API, so users of openxr4j do not need it on their compile classpath.
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-openxr")
    // The drawing half needs the window library (to reach the game's OpenGL
    // context) and OpenGL itself (to wrap the headset's images in framebuffers).
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-openxr::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
}

// Hardware checks that need a real OpenXR runtime (and usually a headset).
// They are not unit tests, so they get their own source folder, src/smoke,
// and are run by hand:  ./gradlew smoke
val smoke = sourceSets.create("smoke") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[smoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[smoke.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Runs the manual hardware check against the live OpenXR runtime."
    classpath = smoke.runtimeClasspath
    mainClass.set("me.corriekay.openxr4j.Smoke")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
