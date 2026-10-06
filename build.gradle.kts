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
    // OpenGL is forwarded (api): drawing into an eye means calling OpenGL, so
    // a program using openxr4j needs it on its compile classpath. OpenXR and
    // the window library stay private: nothing from them appears in the API.
    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl:lwjgl")
    api("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-openxr")
    implementation("org.lwjgl:lwjgl-glfw")
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
