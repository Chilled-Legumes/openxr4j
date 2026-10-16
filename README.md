# openxr4j

A plain-Java library for talking to VR headsets through OpenXR.

"openxr4j" is a working name.

OpenXR's own interface is a C API, and the usual Java route to it (LWJGL) is
a one-to-one translation of that C API: results come back in containers you
pass in, failures are numeric codes, and nothing reads like Java. openxr4j sits on
top of LWJGL and gives it a Java face:

- A single value is returned as a single value: `float trigger(Hand)`.
- A compound value is offered two ways: `Pose gripPose(Hand)` returns a new
  object, and `Pose gripPose(Hand, Pose dest)` fills one you supply, for code
  that runs every frame and wants no garbage.
- Failure is always an exception (`OpenXrException`), never a code to check.
- Nothing from LWJGL appears in openxr4j's public API.

## Who wrote it

openxr4j's code, and this README, were written by Claude, an AI model made
by Anthropic, working in Claude Code at Corrie Kay's direction. Corrie
decides what the library should do, runs it against real hardware, and
commits it, so the commits are under Corrie's name. It was built for
Corrie's VR game and is shared in case it is useful to anyone else.

## Using it

openxr4j is not published to a Maven repository yet. The simplest way in
is a Gradle composite build: clone this repository next to your project,
then in your project's `settings.gradle.kts`

```kotlin
includeBuild("../openxr4j")
```

and in its `build.gradle.kts`

```kotlin
dependencies {
    implementation("me.corriekay:openxr4j")
}
```

Gradle builds openxr4j from source and swaps it in for that dependency.
LWJGL's OpenGL bindings come along with it (a program drawing into an eye
calls OpenGL itself); OpenXR and GLFW stay inside.

## Status

Early. What exists is the input half:

| Feature | State |
|---|---|
| List what the runtime offers (`OpenXr.availableExtensions()`) | Works, checked against SteamVR on Linux |
| Input-only session, no graphics (`VrSession.openInputOnly`) | Works, checked with a Quest Pro over Steam Link on Linux. Blocks until the session has started |
| Controller poses, grips, thumbsticks, controller type | Work, same check. The headset has to be worn, or its controllers stop tracking |
| Triggers and face buttons (A, B, X, Y) | Work, same check |
| Haptics (`VrSession.vibrate`), timed and continuous | Work; felt on both controllers. The continuous form holds until stopped on SteamVR |
| Menu button | Written; did not register on a Quest Pro under SteamVR. SteamVR uses the left menu button to open its own dashboard, so it likely never reaches the application |
| Thumbstick click | Works, same check |
| Drawing to the headset (`VrSession.open(name)` or `open(name, glfwWindow)`, `beginFrame`, `Eye.draw`, `endFrame`, eye and head poses, view and projection matrices, `createWindow`) | Works, both with openxr4j's own hidden window and with a window handed in: 90 Hz on SteamVR, Quest Pro, Linux. OpenGL through GLFW on X11 only |
| World origin on the floor, `floorHeight()`, `playArea()`, `recentre()` | Written 2026-10-08. At open the origin goes on the floor under the head's starting spot, facing its way; `recentre()` does the same again on demand. `floorHeight()` and `playArea()` checked against a Quest Pro on SteamVR (Corrie's grid matched the room); the floor origin and `recentre()` not yet |
| Spectator window (`VrSession.addSpectator`), a third `Eye` in `eyes()` that shows one eye's view on the desktop | Works: 2026-10-15, SteamVR, Quest Pro, Linux (NVIDIA), with openxr4j's own window following the right eye. A window handed in, and following the left eye, not yet tried |
| Windows | Not started. The input-only session is Linux-only so far |

## Example

```java
try (VrSession vr = VrSession.open("My App")) {
    while (!vr.isExitRequested()) {
        vr.update();
        if (vr.isTracked(Hand.RIGHT) && vr.trigger(Hand.RIGHT) > 0.5f) {
            vr.vibrate(Hand.RIGHT, 0.8f, 0.1f);
        }
    }
}
```

`VrSession.open` is the way in. `OpenXr.openSession` is the same call, there
for anyone who goes looking on `OpenXr` first.

`update()` does all the talking to the runtime. Getters return what the last
`update()` read and cost nothing.

## Drawing

`VrSession.open(name)` makes the OpenGL context itself, in a hidden window,
and leaves it current on the calling thread. Everything uploaded to the GPU
lives in that context. A program that already has a GLFW window and context
passes it instead: `VrSession.open(name, window)`.

```java
try (VrSession vr = VrSession.open("My App")) {
    while (!vr.isExitRequested()) {
        glfwPollEvents();
        if (vr.beginFrame()) {            // waits for the headset's frame slot, reads head and hands
            for (Eye eye : vr.eyes()) {
                eye.draw(() -> {          // OpenGL now draws into this eye's picture
                    eye.viewMatrix(view);
                    eye.projectionMatrix(0.05f, 100f, projection);
                    // draw the scene with those two matrices
                });
            }
        }
        vr.endFrame();                    // always, whatever beginFrame returned
    }
}
```

The headset sets the pace: `beginFrame()` blocks until the next frame is
due, so the loop needs no sleep.

Colours are linear. When the eye pictures are sRGB, as on SteamVR, openxr4j
switches on OpenGL's sRGB encoding inside `eye.draw`, so what a shader
writes, and what `glClear` fills with, is taken as linear light and
encoded on the way in. Colours from a glTF material or from Blender go in
as they are; colours picked on a screen (a `java.awt.Color`, a hex code)
are sRGB and come out too light unless converted to linear first.

For a desktop view, `vr.addSpectator()` opens a window that shows what the
right eye sees (`addSpectator(Eye.LEFT)` for the left), and `eyes()` lists
it as a third `Eye` from then on, so the loop above draws it unchanged. It
sees from that eye's pose, with the eye's view from top to bottom fitted
sideways to the window's shape. A program with a window of its own passes
it in: `addSpectator(window)` or `addSpectator(window, Eye.LEFT)`. That
window's context must share the session's (`vr.createWindow` makes one
that does). `eye.isSpectator()` tells the spectator apart, for anything a
program wants to draw differently there.

The spectator is drawn in the session's context, like the eyes, and
`endFrame()` copies the picture onto the window. The window never waits for
the monitor, so it cannot slow the headset down. Closing the window openxr4j
made removes the spectator; so does `removeSpectator()`.

`vr.createWindow(width, height, title)` alone returns a GLFW window whose
context shares the session's. Sharing covers buffers, textures and shaders,
but not vertex array objects or framebuffers, so a mesh drawn through a
vertex array object does not draw there as it is. openxr4j does not draw
into such a window; the program does, switching contexts with
`glfwMakeContextCurrent` and switching back to `vr.window()` before the
next frame.

The input-only kind, controllers and haptics with nothing drawn, is
`VrSession.openInputOnly(name)`.

SteamVR's OpenGL path is X11. openxr4j starts GLFW on X11 itself; a program
that makes its own window on a Wayland desktop must do the same
(`glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)` before `glfwInit`).

## Requirements

- Java 25.
- An OpenXR runtime set as the system's active runtime. The input-only
  session needs the runtime to offer `XR_MND_headless` and
  `XR_KHR_convert_timespec_time`. SteamVR does.
- A headset connected to the runtime. For drawing it has to be worn, or its controllers stop tracking.
