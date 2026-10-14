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

// Machine-local additions, kept out of git, are applied when present.
if (file("local.gradle.kts").exists()) {
    apply(from = "local.gradle.kts")
}
