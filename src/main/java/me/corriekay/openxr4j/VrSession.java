package me.corriekay.openxr4j;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memFree;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.openxr.KHRConvertTimespecTime;
import org.lwjgl.openxr.KHROpenGLEnable;
import org.lwjgl.openxr.XR10;
import org.lwjgl.openxr.XrAction;
import org.lwjgl.openxr.XrActionCreateInfo;
import org.lwjgl.openxr.XrActionSet;
import org.lwjgl.openxr.XrActionSetCreateInfo;
import org.lwjgl.openxr.XrActionSpaceCreateInfo;
import org.lwjgl.openxr.XrActionStateBoolean;
import org.lwjgl.openxr.XrActionStateFloat;
import org.lwjgl.openxr.XrActionStateGetInfo;
import org.lwjgl.openxr.XrActionStateVector2f;
import org.lwjgl.openxr.XrActionSuggestedBinding;
import org.lwjgl.openxr.XrActionsSyncInfo;
import org.lwjgl.openxr.XrActiveActionSet;
import org.lwjgl.openxr.XrApplicationInfo;
import org.lwjgl.openxr.XrEventDataBuffer;
import org.lwjgl.openxr.XrEventDataSessionStateChanged;
import org.lwjgl.openxr.XrExtent2Df;
import org.lwjgl.openxr.XrFovf;
import org.lwjgl.openxr.XrGraphicsBindingOpenGLXlibKHR;
import org.lwjgl.openxr.XrGraphicsRequirementsOpenGLKHR;
import org.lwjgl.openxr.XrHapticActionInfo;
import org.lwjgl.openxr.XrHapticBaseHeader;
import org.lwjgl.openxr.XrHapticVibration;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrInstanceCreateInfo;
import org.lwjgl.openxr.XrInstanceProperties;
import org.lwjgl.openxr.XrInteractionProfileState;
import org.lwjgl.openxr.XrInteractionProfileSuggestedBinding;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrReferenceSpaceCreateInfo;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSessionActionSetsAttachInfo;
import org.lwjgl.openxr.XrSessionBeginInfo;
import org.lwjgl.openxr.XrSessionCreateInfo;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSpaceLocation;
import org.lwjgl.openxr.XrSystemGetInfo;
import org.lwjgl.openxr.XrSystemProperties;
import org.lwjgl.system.MemoryStack;

/**
 * A live connection to the VR runtime that reads the controllers and drives
 * their haptics.
 *
 * <p>This is an input-only session. It draws nothing to the headset, so it
 * needs no window and no graphics. The headset does not have to be worn, but
 * it must be connected to the runtime.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * try (VrSession vr = VrSession.open("My App")) {
 *     while (!vr.isExitRequested()) {
 *         vr.update();
 *         if (vr.isTracked(Hand.RIGHT)) {
 *             Pose pose = vr.gripPose(Hand.RIGHT);
 *             float trigger = vr.trigger(Hand.RIGHT);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #update()} does all the talking to the runtime. Every getter
 * returns the value read by the most recent {@code update()} and does no
 * work of its own, so getters are cheap and create no garbage.
 *
 * <p>A session is not thread-safe. Use it from one thread.
 */
public final class VrSession implements AutoCloseable {

    private static final String EXT_HEADLESS = "XR_MND_headless";
    private static final String EXT_TIMESPEC = "XR_KHR_convert_timespec_time";
    private static final String EXT_OPENGL = "XR_KHR_opengl_enable";

    private static final long START_TIMEOUT_NANOS = 5_000_000_000L;

    private static final long LOCATION_VALID =
            XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT;

    /** Everything read from one hand by the latest update. */
    private static final class HandState {
        boolean tracked;
        final Pose gripPose = new Pose();
        final Pose aimPose = new Pose();
        float trigger;
        float grip;
        boolean primary;
        boolean secondary;
        boolean menu;
        float stickX;
        float stickY;
        boolean stickClick;

        /** One line describing this hand, used by {@link VrSession#toString()}. */
        String describe() {
            if (!tracked) {
                return "untracked";
            }
            StringBuilder buttons = new StringBuilder();
            if (primary) {
                buttons.append(" primary");
            }
            if (secondary) {
                buttons.append(" secondary");
            }
            if (menu) {
                buttons.append(" menu");
            }
            if (stickClick) {
                buttons.append(" stickClick");
            }
            return String.format(java.util.Locale.ROOT, "%s trigger=%.2f grip=%.2f stick=(%+.2f, %+.2f)%s",
                    gripPose, trigger, grip, stickX, stickY, buttons);
        }

        void clearInput() {
            tracked = false;
            trigger = 0f;
            grip = 0f;
            primary = false;
            secondary = false;
            menu = false;
            stickX = 0f;
            stickY = 0f;
            stickClick = false;
        }
    }

    private XrInstance instance;
    private XrSession session;
    private long systemId;
    private XrSpace localSpace;   // the runtime's own: origin at the head's starting spot
    private XrSpace baseSpace;    // what everything is reported in: localSpace moved to the floor, or recentred
    private XrSpace viewSpace;
    private XrSpace stageSpace;
    private final Pose originPose = new Pose();   // baseSpace's origin, in localSpace
    private final Pose stagePose = new Pose();
    private XrActionSet actionSet;

    // Drawing half. Null for an input-only session.
    private long glfwWindow;
    private boolean ownsWindow;
    private boolean hasTimespec;
    private GlDisplay display;
    private final Eye[] eyes = {new Eye(this, 0), new Eye(this, 1)};

    private XrAction gripPoseAction;
    private XrAction aimPoseAction;
    private XrAction triggerAction;
    private XrAction gripAction;
    private XrAction primaryAction;
    private XrAction secondaryAction;
    private XrAction menuAction;
    private XrAction stickAction;
    private XrAction stickClickAction;
    private XrAction hapticAction;

    private final long[] handPaths = new long[2];
    private final XrSpace[] gripSpaces = new XrSpace[2];
    private final XrSpace[] aimSpaces = new XrSpace[2];
    private final HandState[] hands = {new HandState(), new HandState()};

    // Native memory reused on every update, so that updating creates no garbage.
    private XrEventDataBuffer eventBuffer;
    private XrActiveActionSet.Buffer activeActionSets;
    private XrActionsSyncInfo syncInfo;
    private XrActionStateGetInfo getInfo;
    private XrActionStateFloat floatState;
    private XrActionStateBoolean booleanState;
    private XrActionStateVector2f vectorState;
    private XrSpaceLocation location;
    private XrHapticActionInfo hapticInfo;
    private XrHapticVibration hapticVibration;
    private ByteBuffer timespec;
    private LongBuffer timeOut;

    private String runtimeName = "";
    private String runtimeVersion = "";
    private String systemName = "";
    private int boundProfiles;
    private final String[] controllerTypes = {"", ""};
    private boolean controllerTypesStale = true;

    private boolean running;
    private boolean focused;
    private boolean exitRequested;
    private boolean closed;

    private VrSession() {
    }

    /**
     * Connects to the installed OpenXR runtime and opens an input-only session.
     *
     * <p>This call blocks until the runtime has started the session, which is
     * normally a few milliseconds. The session it returns is ready to use:
     * the first {@link #update()} already reads input. Controllers may still
     * take a moment longer to report as tracked, for example while a headset
     * wakes up, so check {@link #isTracked(Hand)}.
     *
     * @param applicationName the name the runtime shows for this program
     * @throws OpenXrException if there is no runtime, no connected headset, or the
     *                      runtime lacks a feature this kind of session needs
     */
    public static VrSession openInputOnly(String applicationName) {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            throw new OpenXrException("Input-only sessions are implemented for Linux so far.");
        }
        List<String> available = OpenXr.availableExtensions();
        if (!available.contains(EXT_HEADLESS)) {
            throw new OpenXrException("The OpenXR runtime does not offer " + EXT_HEADLESS
                    + ", which an input-only session needs.");
        }
        if (!available.contains(EXT_TIMESPEC)) {
            throw new OpenXrException("The OpenXR runtime does not offer " + EXT_TIMESPEC
                    + ", which an input-only session needs.");
        }
        return open(applicationName, 0L, false, new String[] {EXT_HEADLESS, EXT_TIMESPEC}, true);
    }

    /**
     * Connects to the installed OpenXR runtime and opens a full session that
     * draws to the headset, reads the controllers, and drives haptics.
     *
     * <p>openxr4j makes the OpenGL context itself, in a hidden window, and
     * leaves it current on this thread. Everything you upload to the GPU
     * lives in that context. If you later want a desktop window, ask for one
     * with {@link #createWindow(int, int, String)} and it will share it.
     *
     * <p>Per frame: {@link #beginFrame()}, then for each {@link Eye} from
     * {@link #eyes()} call {@link Eye#draw(Runnable)}, then {@link #endFrame()}.
     *
     * @param applicationName the name the runtime shows for this program
     * @throws OpenXrException if there is no runtime, no connected headset, or
     *                         the runtime lacks OpenGL support
     */
    public static VrSession open(String applicationName) {
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        if (!GLFW.glfwInit()) {
            throw new OpenXrException("The window library (GLFW) failed to start.");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, applicationName, 0L, 0L);
        if (window == 0L) {
            throw new OpenXrException("Could not create an OpenGL 3.3 context.");
        }
        GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.createCapabilities();
        // On failure, openWith's own clean-up destroys the window (ownsWindow).
        return openWith(applicationName, window, true);
    }

    /**
     * Connects to the installed OpenXR runtime and opens a full session that
     * draws to the headset through the given window's OpenGL context, as well
     * as reading the controllers and driving haptics.
     *
     * <p>Requirements on the caller: the window was created with GLFW on the
     * X11 platform (on Wayland, set the GLFW platform init hint to X11), its
     * OpenGL context is current on this thread, and LWJGL's OpenGL
     * capabilities have been created for it.
     *
     * <p>Per frame: {@link #beginFrame()}, then for each {@link Eye} from
     * {@link #eyes()} call {@link Eye#draw(Runnable)}, then {@link #endFrame()}.
     * {@code beginFrame()} also reads the controllers, so {@link #update()} is
     * not needed.
     *
     * @param applicationName the name the runtime shows for this program
     * @param glfwWindow      the GLFW window handle whose context openxr4j draws with
     * @throws OpenXrException if there is no runtime, no connected headset, or the
     *                      runtime lacks OpenGL support
     */
    public static VrSession open(String applicationName, long glfwWindow) {
        if (glfwWindow == 0L) {
            throw new IllegalArgumentException("glfwWindow must be a live GLFW window handle.");
        }
        return openWith(applicationName, glfwWindow, false);
    }

    private static VrSession openWith(String applicationName, long glfwWindow, boolean ownsWindow) {
        List<String> available;
        try {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_X11) {
                throw new OpenXrException("openxr4j draws through X11 only so far. On Wayland, call"
                        + " glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11) before glfwInit().");
            }
            available = OpenXr.availableExtensions();
            if (!available.contains(EXT_OPENGL)) {
                throw new OpenXrException("The OpenXR runtime does not offer " + EXT_OPENGL + ".");
            }
        } catch (RuntimeException e) {
            // Nothing owns the window yet; from the open() below, VrSession.close() does.
            if (ownsWindow) {
                GLFW.glfwDestroyWindow(glfwWindow);
            }
            throw e;
        }
        boolean timespec = available.contains(EXT_TIMESPEC);
        String[] extensions = timespec ? new String[] {EXT_OPENGL, EXT_TIMESPEC} : new String[] {EXT_OPENGL};
        return open(applicationName, glfwWindow, ownsWindow, extensions, timespec);
    }

    private static VrSession open(String applicationName, long glfwWindow, boolean ownsWindow,
            String[] extensions, boolean timespec) {
        VrSession vr = new VrSession();
        vr.glfwWindow = glfwWindow;
        vr.ownsWindow = ownsWindow;
        vr.hasTimespec = timespec;
        try {
            vr.createInstance(applicationName, extensions);
            vr.createSession();
            vr.createActions();
            vr.allocateReusableMemory();
            if (glfwWindow != 0L) {
                vr.display = new GlDisplay(vr.instance, vr.session, vr.baseSpace);
            }
            vr.waitUntilRunning();
            vr.placeOriginOnFloor();
            return vr;
        } catch (RuntimeException e) {
            vr.close();
            throw e;
        }
    }

    /**
     * Milliseconds between headset refreshes, as the runtime reports it each
     * frame (about 11.1 at 90 Hz). Valid after the first {@link #beginFrame()};
     * 0 before that, or in an input-only session.
     */
    public float framePeriodMillis() {
        ensureOpen();
        return display == null ? 0f : display.displayPeriod() / 1_000_000f;
    }

    /**
     * Seconds between this frame's display time and the previous frame's,
     * as the runtime predicted them: the honest {@code dt} for motion. A
     * dropped frame shows up as two periods, where {@link #framePeriodMillis()}
     * would still say one. 0 until the second {@link #beginFrame()}, and in an
     * input-only session.
     */
    public float frameDeltaSeconds() {
        ensureOpen();
        return display == null ? 0f : display.displayDelta() / 1_000_000_000f;
    }

    /**
     * The headset's refresh rate in Hz, from the same report as
     * {@link #framePeriodMillis()}. 0 before the first frame, or in an
     * input-only session.
     */
    public float refreshRate() {
        ensureOpen();
        long period = display == null ? 0L : display.displayPeriod();
        return period == 0L ? 0f : 1_000_000_000f / period;
    }

    /** Whether this session draws to the headset, as opposed to reading input only. */
    public boolean canDraw() {
        return display != null;
    }

    /**
     * The GLFW window that holds this session's OpenGL context: the hidden one
     * openxr4j made, or the one you handed in. Drawing sessions only.
     */
    public long window() {
        ensureDrawing();
        return glfwWindow;
    }

    /**
     * Creates a visible desktop window whose OpenGL context shares this
     * session's, so meshes, textures and shaders uploaded once draw to both
     * the headset and the window. Returns the GLFW window handle.
     *
     * <p>openxr4j does not draw into it. To draw there, make its context
     * current with {@code glfwMakeContextCurrent(handle)}, draw, swap its
     * buffers, and make the session's context current again before the next
     * {@link #beginFrame()}: {@code glfwMakeContextCurrent(vr.window())}.
     * Destroy it with {@code glfwDestroyWindow} when done.
     */
    public long createWindow(int width, int height, String title) {
        ensureDrawing();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long handle = GLFW.glfwCreateWindow(width, height, title, 0L, glfwWindow);
        if (handle == 0L) {
            throw new OpenXrException("Could not create a window sharing the session's context.");
        }
        return handle;
    }

    /** The two eyes, left then right, for use inside a frame. Drawing sessions only. */
    public Eye[] eyes() {
        ensureDrawing();
        return eyes.clone();
    }

    /** The left eye. Drawing sessions only. */
    public Eye leftEye() {
        ensureDrawing();
        return eyes[0];
    }

    /** The right eye. Drawing sessions only. */
    public Eye rightEye() {
        ensureDrawing();
        return eyes[1];
    }

    // ------------------------------------------------------------------
    // Frame (drawing sessions only)
    // ------------------------------------------------------------------

    /**
     * Waits for the headset's next frame slot, starts the frame, locates the
     * eyes and the head, and reads the controllers.
     *
     * @return true if the frame should be drawn; false if the headset is not
     *         showing this program right now, in which case skip drawing and
     *         still call {@link #endFrame()}
     */
    public boolean beginFrame() {
        ensureOpen();
        ensureDrawing();
        if (ownsWindow) {
            GLFW.glfwPollEvents(); // the hidden window is ours, so its housekeeping is too
        }
        pollEvents();
        if (!running) {
            clearHands();
            return false;
        }
        boolean draw = display.beginFrame();
        readInput(display.displayTime());
        return draw;
    }

    /**
     * Makes one eye's image the current draw target, with the viewport set to
     * cover it, and returns the OpenGL framebuffer id. Draw that eye's view
     * after calling this.
     *
     * @param eye 0 for the left eye, 1 for the right
     */
    public int bindEye(int eye) {
        ensureDrawing();
        checkEye(eye);
        currentEye = eyes[eye];
        return display.bindEye(eye);
    }

    private Eye currentEye;

    void clearCurrentEye() {
        currentEye = null;
    }

    /**
     * The eye whose picture is the current draw target: the one whose
     * {@link Eye#draw(Runnable)} or {@link #bindEye(int)} was last called
     * this frame, or {@code null} outside an eye's draw (before the first
     * bind, after {@code Eye.draw} returns, and after {@link #endFrame()}).
     */
    public Eye currentEye() {
        return currentEye;
    }

    /** Hands the frame to the headset. Call once per frame after {@link #beginFrame()}, whatever it returned. */
    public void endFrame() {
        ensureOpen();
        ensureDrawing();
        currentEye = null;
        if (running) {
            display.endFrame();
        }
    }

    /** Width in pixels of an eye's image. */
    public int eyeWidth(int eye) {
        ensureDrawing();
        checkEye(eye);
        return display.width[eye];
    }

    /** Height in pixels of an eye's image. */
    public int eyeHeight(int eye) {
        ensureDrawing();
        checkEye(eye);
        return display.height[eye];
    }

    /** The pose of an eye for the current frame, as located by {@link #beginFrame()}. Returns a new {@link Pose}. */
    public Pose eyePose(int eye) {
        return eyePose(eye, new Pose());
    }

    /** Fills {@code dest} with the eye's pose for the current frame and returns it. Creates no garbage. */
    public Pose eyePose(int eye, Pose dest) {
        ensureDrawing();
        checkEye(eye);
        XrPosef pose = display.eyePose(eye);
        return dest.set(pose.position$().x(), pose.position$().y(), pose.position$().z(),
                pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w());
    }

    /**
     * Fills {@code dest} with a 4x4 OpenGL projection matrix for an eye, column
     * major as OpenGL expects, using the eye's field of view for the current
     * frame. {@code dest} must hold 16 floats.
     */
    public float[] projectionMatrix(int eye, float near, float far, float[] dest) {
        ensureDrawing();
        checkEye(eye);
        if (dest == null || dest.length < 16) {
            throw new IllegalArgumentException("dest must hold 16 floats.");
        }
        XrFovf fov = display.eyeFov(eye);
        float left = (float) Math.tan(fov.angleLeft());
        float right = (float) Math.tan(fov.angleRight());
        float up = (float) Math.tan(fov.angleUp());
        float down = (float) Math.tan(fov.angleDown());
        float width = right - left;
        float height = up - down;
        java.util.Arrays.fill(dest, 0, 16, 0f);
        dest[0] = 2f / width;
        dest[5] = 2f / height;
        dest[8] = (right + left) / width;
        dest[9] = (up + down) / height;
        dest[10] = -(far + near) / (far - near);
        dest[11] = -1f;
        dest[14] = -(2f * far * near) / (far - near);
        return dest;
    }

    /**
     * Fills {@code dest} with a 4x4 OpenGL view matrix for an eye: the matrix
     * that moves the world into that eye's view. Column major. {@code dest}
     * must hold 16 floats.
     */
    public float[] viewMatrix(int eye, float[] dest) {
        ensureDrawing();
        checkEye(eye);
        if (dest == null || dest.length < 16) {
            throw new IllegalArgumentException("dest must hold 16 floats.");
        }
        XrPosef pose = display.eyePose(eye);
        float qx = pose.orientation().x();
        float qy = pose.orientation().y();
        float qz = pose.orientation().z();
        float qw = pose.orientation().w();
        float px = pose.position$().x();
        float py = pose.position$().y();
        float pz = pose.position$().z();

        // Rotation matrix of the pose, then transposed (its inverse), with the
        // translation carried through the inverse as well.
        float r00 = 1 - 2 * (qy * qy + qz * qz);
        float r01 = 2 * (qx * qy - qz * qw);
        float r02 = 2 * (qx * qz + qy * qw);
        float r10 = 2 * (qx * qy + qz * qw);
        float r11 = 1 - 2 * (qx * qx + qz * qz);
        float r12 = 2 * (qy * qz - qx * qw);
        float r20 = 2 * (qx * qz - qy * qw);
        float r21 = 2 * (qy * qz + qx * qw);
        float r22 = 1 - 2 * (qx * qx + qy * qy);

        // Column major: dest[col * 4 + row]. Inverse rotation is the transpose,
        // so row i of R becomes column i.
        dest[0] = r00; dest[1] = r01; dest[2] = r02; dest[3] = 0f;
        dest[4] = r10; dest[5] = r11; dest[6] = r12; dest[7] = 0f;
        dest[8] = r20; dest[9] = r21; dest[10] = r22; dest[11] = 0f;
        dest[12] = -(r00 * px + r10 * py + r20 * pz);
        dest[13] = -(r01 * px + r11 * py + r21 * pz);
        dest[14] = -(r02 * px + r12 * py + r22 * pz);
        dest[15] = 1f;
        return dest;
    }

    /**
     * Fills {@code dest} with the one matrix most programs want per eye:
     * projection times view, so a world-space position multiplied by it lands
     * on that eye's screen. Column major. {@code dest} must hold 16 floats.
     * Creates no garbage.
     */
    public float[] viewProjectionMatrix(int eye, float near, float far, float[] dest) {
        if (dest == null || dest.length < 16) {
            throw new IllegalArgumentException("dest must hold 16 floats.");
        }
        projectionMatrix(eye, near, far, scratchProjection);
        viewMatrix(eye, scratchView);
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += scratchProjection[k * 4 + row] * scratchView[col * 4 + k];
                }
                dest[col * 4 + row] = sum;
            }
        }
        return dest;
    }

    private final float[] scratchProjection = new float[16];
    private final float[] scratchView = new float[16];
    private float near = 0.05f;
    private float far = 1000f;

    /**
     * Sets the closest and farthest distances, in metres, that the eyes draw;
     * anything nearer or farther is clipped. Defaults: 0.05 and 1000. Used by
     * the matrix methods that take no distances.
     */
    public void setDrawDistances(float near, float far) {
        if (!(near > 0f) || !(far > near)) {
            throw new IllegalArgumentException("Need 0 < near < far; got " + near + " and " + far + ".");
        }
        this.near = near;
        this.far = far;
    }

    /** The near draw distance in metres. */
    public float nearDistance() {
        return near;
    }

    /** The far draw distance in metres. */
    public float farDistance() {
        return far;
    }

    /** {@link #viewProjectionMatrix(int, float, float, float[])} with the session's draw distances. */
    public float[] viewProjectionMatrix(int eye, float[] dest) {
        return viewProjectionMatrix(eye, near, far, dest);
    }

    /** {@link #projectionMatrix(int, float, float, float[])} with the session's draw distances. */
    public float[] projectionMatrix(int eye, float[] dest) {
        return projectionMatrix(eye, near, far, dest);
    }

    /**
     * Moves the world's origin to the floor directly under the head, facing
     * the way the head faces now (yaw only; the floor stays level). Every
     * position openxr4j reports afterwards, hands, head and eyes, is relative
     * to that spot. This is the same thing {@code open} does once at the
     * start, and it is independent of the runtime's own recentre.
     *
     * <p>Call it between frames, not between {@code beginFrame()} and
     * {@code endFrame()}.
     *
     * @throws OpenXrException if the head cannot be located right now
     */
    public void recentre() {
        ensureOpen();
        long time = display != null && display.isFrameOpen() ? display.displayTime() : now();
        Pose head = new Pose();
        if (!locate(viewSpace, localSpace, time, head)) {
            throw new OpenXrException("The runtime cannot locate the head right now.");
        }
        float floorY = 0f;
        Pose stage = new Pose();
        if (stageSpace != null && locate(stageSpace, localSpace, time, stage)) {
            floorY = stage.y();
        }
        float yaw = yawOf(head.qx(), head.qy(), head.qz(), head.qw());
        setOrigin(head.x(), floorY, head.z(), yaw);
    }

    /**
     * At open: put the origin on the floor under the head's starting spot.
     * If the runtime has no floor or cannot be asked yet, the origin stays at
     * the head's starting spot (the runtime's own default).
     */
    private void placeOriginOnFloor() {
        if (stageSpace == null) {
            return;
        }
        try {
            Pose stage = new Pose();
            if (locate(stageSpace, localSpace, now(), stage)) {
                setOrigin(0f, stage.y(), 0f, 0f);
            }
        } catch (OpenXrException e) {
            // No usable clock before the first frame on this runtime: leave the origin at the head.
        }
    }

    /** Recreates baseSpace as localSpace moved to (x, y, z) and turned by yaw (radians) about vertical. */
    private void setOrigin(float x, float y, float z, float yaw) {
        float sy = (float) Math.sin(yaw / 2);
        float cy = (float) Math.cos(yaw / 2);
        originPose.set(x, y, z, 0f, sy, 0f, cy);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrReferenceSpaceCreateInfo info = XrReferenceSpaceCreateInfo.calloc(stack)
                    .type$Default()
                    .referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_LOCAL);
            info.poseInReferenceSpace().orientation(q -> q.x(0f).y(sy).z(0f).w(cy));
            info.poseInReferenceSpace().position$(p -> p.x(x).y(y).z(z));
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Moving the world origin", XR10.xrCreateReferenceSpace(session, info, handle));
            XrSpace old = baseSpace;
            baseSpace = new XrSpace(handle.get(0), session);
            if (display != null) {
                display.setSpace(baseSpace);
            }
            if (old != null) {
                XR10.xrDestroySpace(old);
            }
        }
    }

    /** Heading about the vertical axis, in radians, of a quaternion. */
    private static float yawOf(float qx, float qy, float qz, float qw) {
        return (float) Math.atan2(2 * (qw * qy + qx * qz), 1 - 2 * (qy * qy + qz * qz));
    }

    /**
     * The floor's height in the same space as the hands and head, as set in
     * the runtime's room setup. openxr4j puts the world's origin on the floor
     * at open, so this is normally 0; it is here for runtimes without a floor
     * space, where the origin stays at the head's starting spot and this
     * throws.
     *
     * @throws OpenXrException if the runtime has no floor-centred space, or
     *                         cannot locate it right now
     */
    public float floorHeight() {
        locateStage();
        return stagePose.y();
    }

    /**
     * The floor rectangle from room setup, in the same space as the hands and
     * head. See {@link PlayArea}.
     *
     * @throws OpenXrException if the runtime has no floor-centred space, or
     *                         cannot locate it right now
     */
    public PlayArea playArea() {
        locateStage();
        float qx = stagePose.qx();
        float qy = stagePose.qy();
        float qz = stagePose.qz();
        float qw = stagePose.qw();
        float yaw = (float) Math.toDegrees(yawOf(qx, qy, qz, qw));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrExtent2Df bounds = XrExtent2Df.calloc(stack);
            int result = Results.check("Reading the play area",
                    XR10.xrGetReferenceSpaceBoundsRect(session, XR10.XR_REFERENCE_SPACE_TYPE_STAGE, bounds));
            if (result == XR10.XR_SPACE_BOUNDS_UNAVAILABLE) {
                return new PlayArea(stagePose.x(), stagePose.z(), yaw, 0f, 0f);
            }
            return new PlayArea(stagePose.x(), stagePose.z(), yaw, bounds.width(), bounds.height());
        }
    }

    private void locateStage() {
        ensureOpen();
        if (stageSpace == null) {
            throw new OpenXrException("This runtime does not report a floor.");
        }
        long time = display != null && display.isFrameOpen() ? display.displayTime() : now();
        if (!locate(stageSpace, time, stagePose)) {
            throw new OpenXrException("The runtime cannot locate the floor right now.");
        }
    }

    /** Where the head is, in the same space as the hands. Returns a new {@link Pose}. */
    public Pose headPose() {
        return headPose(new Pose());
    }

    /** Fills {@code dest} with the head pose and returns it. Creates no garbage. */
    public Pose headPose(Pose dest) {
        ensureOpen();
        if (!running) {
            return dest;
        }
        locate(viewSpace, display != null && display.isFrameOpen() ? display.displayTime() : now(), dest);
        return dest;
    }

    private void ensureDrawing() {
        if (display == null) {
            throw new IllegalStateException("This session was opened input-only. Open it with a window to draw.");
        }
    }

    private static void checkEye(int eye) {
        if (eye < 0 || eye >= GlDisplay.EYES) {
            throw new IllegalArgumentException("eye must be 0 (left) or 1 (right).");
        }
    }

    // ------------------------------------------------------------------
    // Per-frame
    // ------------------------------------------------------------------

    /**
     * Talks to the runtime: handles session changes and reads both hands.
     * Call once per frame or tick, before using the getters.
     */
    public void update() {
        ensureOpen();
        if (display != null && display.isFrameOpen()) {
            return; // beginFrame() already read input for this frame
        }
        pollEvents();
        if (!running) {
            clearHands();
            return;
        }

        readInput(now());
    }

    /** Syncs the input set and reads both hands as of the given runtime time. */
    private void readInput(long time) {
        int result = Results.check("Syncing input", XR10.xrSyncActions(session, syncInfo));
        if (result == XR10.XR_SESSION_NOT_FOCUSED) {
            focused = false;
            clearHands();
            return;
        }

        if (controllerTypesStale) {
            refreshControllerTypes();
        }

        for (Hand hand : Hand.values()) {
            readHand(hand, time);
        }
    }

    /** Whether the runtime is currently delivering input to this session. */
    public boolean isFocused() {
        return focused;
    }

    /** Whether the runtime has asked this session to end, or the connection is being lost. */
    public boolean isExitRequested() {
        return exitRequested;
    }

    // ------------------------------------------------------------------
    // Hands
    // ------------------------------------------------------------------

    private final Controller[] controllers = {new Controller(this, Hand.LEFT), new Controller(this, Hand.RIGHT)};

    /**
     * A long-lived handle on one hand's controller. Always the same object
     * for the same hand, so take it once and keep it; its reads follow the
     * session's state frame by frame. See {@link Controller}.
     */
    public Controller controller(Hand hand) {
        return controllers[hand.ordinal()];
    }

    /** Whether the hand's controller currently has a valid position and orientation. */
    public boolean isTracked(Hand hand) {
        return hands[hand.ordinal()].tracked;
    }

    /**
     * Where the hand is holding the controller: the pose of the grip.
     * Returns a new {@link Pose}. If the hand is not tracked, this is its last known pose.
     */
    public Pose gripPose(Hand hand) {
        return gripPose(hand, new Pose());
    }

    /** Fills {@code dest} with the grip pose and returns it. Creates no garbage. */
    public Pose gripPose(Hand hand, Pose dest) {
        return dest.set(hands[hand.ordinal()].gripPose);
    }

    /**
     * Where the controller is pointing: a pose whose -Z axis is the pointing ray.
     * Returns a new {@link Pose}. If the hand is not tracked, this is its last known pose.
     */
    public Pose aimPose(Hand hand) {
        return aimPose(hand, new Pose());
    }

    /** Fills {@code dest} with the aim pose and returns it. Creates no garbage. */
    public Pose aimPose(Hand hand, Pose dest) {
        return dest.set(hands[hand.ordinal()].aimPose);
    }

    /** How far the index-finger trigger is pulled, from 0 to 1. */
    public float trigger(Hand hand) {
        return hands[hand.ordinal()].trigger;
    }

    /** How hard the grip is squeezed, from 0 to 1. */
    public float grip(Hand hand) {
        return hands[hand.ordinal()].grip;
    }

    /** The lower face button: A on the right hand, X on the left, on Touch-style controllers. */
    public boolean primaryButton(Hand hand) {
        return hands[hand.ordinal()].primary;
    }

    /** The upper face button: B on the right hand, Y on the left, on Touch-style controllers. */
    public boolean secondaryButton(Hand hand) {
        return hands[hand.ordinal()].secondary;
    }

    /** The menu button. Many controllers have one on the left hand only. */
    public boolean menuButton(Hand hand) {
        return hands[hand.ordinal()].menu;
    }

    /** Thumbstick left-right position, from -1 (left) to 1 (right). */
    public float thumbstickX(Hand hand) {
        return hands[hand.ordinal()].stickX;
    }

    /** Thumbstick forward-back position, from -1 (back) to 1 (forward). */
    public float thumbstickY(Hand hand) {
        return hands[hand.ordinal()].stickY;
    }

    /** Whether the thumbstick is pressed in. */
    public boolean thumbstickPressed(Hand hand) {
        return hands[hand.ordinal()].stickClick;
    }

    // ------------------------------------------------------------------
    // Haptics
    // ------------------------------------------------------------------

    /**
     * Vibrates a controller continuously until {@link #stopVibration(Hand)} is
     * called or another vibrate call replaces it. Call it again with a new
     * strength to change the strength without a gap. Strength 0 stops it.
     *
     * @param strength from 0 (off) to 1 (strongest); values outside are clamped
     */
    public void vibrate(Hand hand, float strength) {
        if (strength <= 0f) {
            stopVibration(hand);
            return;
        }
        applyVibration(hand, strength, XR10.XR_INFINITE_DURATION, 0f);
    }

    /**
     * Vibrates a controller at the runtime's default frequency.
     *
     * @param strength from 0 (off) to 1 (strongest); values outside are clamped
     * @param seconds  how long to vibrate
     */
    public void vibrate(Hand hand, float strength, float seconds) {
        vibrate(hand, strength, seconds, 0f);
    }

    /**
     * Vibrates a controller. A new call replaces any vibration still playing on that hand.
     *
     * @param strength    from 0 (off) to 1 (strongest); values outside are clamped
     * @param seconds     how long to vibrate
     * @param frequencyHz vibration frequency in hertz, or 0 for the runtime's default
     */
    public void vibrate(Hand hand, float strength, float seconds, float frequencyHz) {
        applyVibration(hand, strength, Math.max(1L, (long) (seconds * 1_000_000_000.0)), frequencyHz);
    }

    private void applyVibration(Hand hand, float strength, long durationNanos, float frequencyHz) {
        ensureOpen();
        if (!running) {
            return;
        }
        hapticInfo.subactionPath(handPaths[hand.ordinal()]);
        hapticVibration
                .duration(durationNanos)
                .frequency(Math.max(0f, frequencyHz))
                .amplitude(Math.max(0f, Math.min(1f, strength)));
        Results.check("Starting vibration", XR10.xrApplyHapticFeedback(
                session, hapticInfo, XrHapticBaseHeader.create(hapticVibration.address())));
    }

    /** Stops any vibration playing on a controller. */
    public void stopVibration(Hand hand) {
        ensureOpen();
        if (!running) {
            return;
        }
        hapticInfo.subactionPath(handPaths[hand.ordinal()]);
        Results.check("Stopping vibration", XR10.xrStopHapticFeedback(session, hapticInfo));
    }

    // ------------------------------------------------------------------
    // About the runtime
    // ------------------------------------------------------------------

    /** The runtime's name, for example {@code SteamVR/OpenXR}. */
    public String runtimeName() {
        return runtimeName;
    }

    /** The runtime's version, as {@code major.minor.patch}. */
    public String runtimeVersion() {
        return runtimeVersion;
    }

    /** The name the runtime gives the connected headset system. */
    public String systemName() {
        return systemName;
    }

    /**
     * The kind of controller the runtime says this hand is holding, as an
     * OpenXR interaction profile path such as
     * {@code /interaction_profiles/oculus/touch_controller}. Empty if the
     * runtime has not matched this hand to any controller type openxr4j knows.
     */
    public String controllerType(Hand hand) {
        return controllerTypes[hand.ordinal()];
    }

    /**
     * How many controller types openxr4j managed to register button layouts for.
     * Zero would mean no controller can deliver input.
     */
    public int supportedControllerTypes() {
        return boundProfiles;
    }

    /**
     * A readable snapshot of the session for logs and debugging: the runtime,
     * the session's state, and what each hand read at the last update. For example:
     *
     * <pre>
     * VrSession[SteamVR/OpenXR 2.18.2, focused,
     *   LEFT: pos(-0.078, -0.177, 0.222) rot(-0.941, -0.155, -0.147, -0.264) trigger=0.07 grip=0.86 stick=(+0.45, +0.46),
     *   RIGHT: untracked]
     * </pre>
     */
    @Override
    public String toString() {
        String state;
        if (closed) {
            state = "closed";
        } else if (exitRequested) {
            state = "exit requested";
        } else if (focused) {
            state = "focused";
        } else if (running) {
            state = "running, not focused";
        } else {
            state = "waiting for the runtime";
        }
        return "VrSession[" + runtimeName + " " + runtimeVersion + ", " + state
                + ",\n  LEFT: " + hands[Hand.LEFT.ordinal()].describe()
                + ",\n  RIGHT: " + hands[Hand.RIGHT.ordinal()].describe() + "]";
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    /** Ends the session and releases everything. Safe to call more than once. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        focused = false;

        if (display != null) {
            display.close();
            display = null;
        }
        if (ownsWindow && glfwWindow != 0L) {
            GLFW.glfwDestroyWindow(glfwWindow);
            glfwWindow = 0L;
        }
        if (viewSpace != null) {
            XR10.xrDestroySpace(viewSpace);
        }
        if (stageSpace != null) {
            XR10.xrDestroySpace(stageSpace);
        }
        if (localSpace != null) {
            XR10.xrDestroySpace(localSpace);
        }
        for (int i = 0; i < 2; i++) {
            if (gripSpaces[i] != null) {
                XR10.xrDestroySpace(gripSpaces[i]);
            }
            if (aimSpaces[i] != null) {
                XR10.xrDestroySpace(aimSpaces[i]);
            }
        }
        if (baseSpace != null) {
            XR10.xrDestroySpace(baseSpace);
        }
        if (actionSet != null) {
            XR10.xrDestroyActionSet(actionSet);
        }
        if (session != null) {
            XR10.xrDestroySession(session);
        }
        if (instance != null) {
            XR10.xrDestroyInstance(instance);
        }

        if (eventBuffer != null) {
            eventBuffer.free();
        }
        if (activeActionSets != null) {
            activeActionSets.free();
        }
        if (syncInfo != null) {
            syncInfo.free();
        }
        if (getInfo != null) {
            getInfo.free();
        }
        if (floatState != null) {
            floatState.free();
        }
        if (booleanState != null) {
            booleanState.free();
        }
        if (vectorState != null) {
            vectorState.free();
        }
        if (location != null) {
            location.free();
        }
        if (hapticInfo != null) {
            hapticInfo.free();
        }
        if (hapticVibration != null) {
            hapticVibration.free();
        }
        if (timespec != null) {
            memFree(timespec);
        }
        if (timeOut != null) {
            memFree(timeOut);
        }
    }

    // ==================================================================
    // Setup
    // ==================================================================

    private void createInstance(String applicationName, String[] extensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrApplicationInfo appInfo = XrApplicationInfo.calloc(stack)
                    .applicationName(stack.UTF8(fit(applicationName, 127)))
                    .applicationVersion(1)
                    .engineName(stack.UTF8("openxr4j"))
                    .engineVersion(1)
                    .apiVersion(XR10.XR_MAKE_VERSION(1, 0, 0));

            PointerBuffer extensionNames = stack.mallocPointer(extensions.length);
            for (String extension : extensions) {
                extensionNames.put(stack.UTF8(extension));
            }
            extensionNames.flip();
            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type$Default()
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(extensionNames);

            PointerBuffer handle = stack.mallocPointer(1);
            int result = XR10.xrCreateInstance(createInfo, handle);
            if (result == XR10.XR_ERROR_RUNTIME_FAILURE) {
                // SteamVR answers this way when it has no headset to talk to.
                throw new OpenXrException("Connecting to the OpenXR runtime (is the headset connected and the"
                        + " runtime running?)", result);
            }
            Results.check("Connecting to the OpenXR runtime", result);
            instance = new XrInstance(handle.get(0), createInfo);

            XrInstanceProperties properties = XrInstanceProperties.calloc(stack).type$Default();
            Results.check("Reading runtime details", XR10.xrGetInstanceProperties(instance, properties));
            runtimeName = properties.runtimeNameString();
            long version = properties.runtimeVersion();
            runtimeVersion = (version >>> 48) + "." + ((version >>> 32) & 0xFFFF) + "." + (version & 0xFFFFFFFFL);
        }
    }

    private void createSession() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrSystemGetInfo systemInfo = XrSystemGetInfo.calloc(stack)
                    .type$Default()
                    .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);
            LongBuffer systemIdOut = stack.mallocLong(1);
            int result = XR10.xrGetSystem(instance, systemInfo, systemIdOut);
            if (result == XR10.XR_ERROR_FORM_FACTOR_UNAVAILABLE) {
                throw new OpenXrException("No headset is connected to the OpenXR runtime.");
            }
            Results.check("Finding the headset", result);
            systemId = systemIdOut.get(0);

            XrSystemProperties systemProperties = XrSystemProperties.calloc(stack).type$Default();
            Results.check("Reading headset details",
                    XR10.xrGetSystemProperties(instance, systemId, systemProperties));
            systemName = systemProperties.systemNameString();

            XrSessionCreateInfo sessionInfo = XrSessionCreateInfo.calloc(stack)
                    .type$Default()
                    .systemId(systemId);
            if (glfwWindow != 0L) {
                // The runtime insists on being asked about OpenGL before a session is made.
                XrGraphicsRequirementsOpenGLKHR requirements =
                        XrGraphicsRequirementsOpenGLKHR.calloc(stack).type$Default();
                Results.check("Checking OpenGL requirements",
                        KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR(instance, systemId, requirements));

                // The runtime wants the exact framebuffer configuration the context was
                // made with, and the X visual that goes with it. GLFW doesn't hand those
                // out, so ask GLX for them by the context's config id.
                long xDisplay = GLFWNativeX11.glfwGetX11Display();
                long glxContext = GLFWNativeGLX.glfwGetGLXContext(glfwWindow);
                int screen = org.lwjgl.system.linux.X11.XDefaultScreen(xDisplay);
                org.lwjgl.opengl.GL.createCapabilitiesGLX(xDisplay, screen);
                IntBuffer configId = stack.mallocInt(1);
                org.lwjgl.opengl.GLX13.glXQueryContext(xDisplay, glxContext, org.lwjgl.opengl.GLX13.GLX_FBCONFIG_ID, configId);
                PointerBuffer configs = org.lwjgl.opengl.GLX13.glXChooseFBConfig(xDisplay, screen,
                        stack.ints(org.lwjgl.opengl.GLX13.GLX_FBCONFIG_ID, configId.get(0), 0));
                if (configs == null || configs.remaining() == 0) {
                    throw new OpenXrException("Could not find the window's GLX framebuffer configuration.");
                }
                long fbConfig = configs.get(0);
                IntBuffer visualId = stack.mallocInt(1);
                org.lwjgl.opengl.GLX13.glXGetFBConfigAttrib(xDisplay, fbConfig, org.lwjgl.opengl.GLX13.GLX_VISUAL_ID, visualId);
                org.lwjgl.system.linux.X11.XFree(configs);

                XrGraphicsBindingOpenGLXlibKHR binding = XrGraphicsBindingOpenGLXlibKHR.calloc(stack)
                        .type$Default()
                        .xDisplay(xDisplay)
                        .visualid(visualId.get(0))
                        .glxFBConfig(fbConfig)
                        .glxDrawable(GLFWNativeGLX.glfwGetGLXWindow(glfwWindow))
                        .glxContext(glxContext);
                sessionInfo.next(binding.address());
            }
            // With no graphics binding chained on, this is a headless session.
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Opening the session", XR10.xrCreateSession(instance, sessionInfo, handle));
            session = new XrSession(handle.get(0), instance);
            SYSTEM_IDS.put(session.address(), systemId);

            XrReferenceSpaceCreateInfo spaceInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                    .type$Default()
                    .referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_LOCAL)
                    .poseInReferenceSpace(VrSession::identity);
            Results.check("Creating the reference space",
                    XR10.xrCreateReferenceSpace(session, spaceInfo, handle));
            localSpace = new XrSpace(handle.get(0), session);
            Results.check("Creating the world space",
                    XR10.xrCreateReferenceSpace(session, spaceInfo, handle));
            baseSpace = new XrSpace(handle.get(0), session);

            XrReferenceSpaceCreateInfo viewInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                    .type$Default()
                    .referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_VIEW)
                    .poseInReferenceSpace(VrSession::identity);
            Results.check("Creating the head space", XR10.xrCreateReferenceSpace(session, viewInfo, handle));
            viewSpace = new XrSpace(handle.get(0), session);

            // The floor-centred space, if the runtime offers one (most do).
            IntBuffer count = stack.mallocInt(1);
            Results.check("Counting reference spaces", XR10.xrEnumerateReferenceSpaces(session, count, null));
            IntBuffer types = stack.mallocInt(count.get(0));
            Results.check("Listing reference spaces", XR10.xrEnumerateReferenceSpaces(session, count, types));
            for (int i = 0; i < types.limit(); i++) {
                if (types.get(i) == XR10.XR_REFERENCE_SPACE_TYPE_STAGE) {
                    XrReferenceSpaceCreateInfo stageInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                            .type$Default()
                            .referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_STAGE)
                            .poseInReferenceSpace(VrSession::identity);
                    Results.check("Creating the floor space",
                            XR10.xrCreateReferenceSpace(session, stageInfo, handle));
                    stageSpace = new XrSpace(handle.get(0), session);
                }
            }
        }
    }

    private void createActions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer path = stack.mallocLong(1);
            for (Hand hand : Hand.values()) {
                handPaths[hand.ordinal()] = path(hand.userPath, path);
            }

            XrActionSetCreateInfo setInfo = XrActionSetCreateInfo.calloc(stack)
                    .type$Default()
                    .actionSetName(stack.UTF8("openxr4j"))
                    .localizedActionSetName(stack.UTF8("openxr4j input"))
                    .priority(0);
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Creating the input set", XR10.xrCreateActionSet(instance, setInfo, handle));
            actionSet = new XrActionSet(handle.get(0), instance);

            gripPoseAction = action("grip_pose", "Grip pose", XR10.XR_ACTION_TYPE_POSE_INPUT);
            aimPoseAction = action("aim_pose", "Aim pose", XR10.XR_ACTION_TYPE_POSE_INPUT);
            triggerAction = action("trigger", "Trigger", XR10.XR_ACTION_TYPE_FLOAT_INPUT);
            gripAction = action("grip", "Grip", XR10.XR_ACTION_TYPE_FLOAT_INPUT);
            primaryAction = action("primary_button", "Primary button", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            secondaryAction = action("secondary_button", "Secondary button", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            menuAction = action("menu_button", "Menu button", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            stickAction = action("thumbstick", "Thumbstick", XR10.XR_ACTION_TYPE_VECTOR2F_INPUT);
            stickClickAction = action("thumbstick_click", "Thumbstick click", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            hapticAction = action("haptic", "Haptic", XR10.XR_ACTION_TYPE_VIBRATION_OUTPUT);

            suggestBindings();

            XrSessionActionSetsAttachInfo attachInfo = XrSessionActionSetsAttachInfo.calloc(stack)
                    .type$Default()
                    .actionSets(stack.pointers(actionSet.address()));
            Results.check("Attaching the input set", XR10.xrAttachSessionActionSets(session, attachInfo));

            for (Hand hand : Hand.values()) {
                int i = hand.ordinal();
                gripSpaces[i] = actionSpace(gripPoseAction, handPaths[i]);
                aimSpaces[i] = actionSpace(aimPoseAction, handPaths[i]);
            }
        }
    }

    /**
     * Tells the runtime which physical control feeds each input, per controller type.
     * A controller type the runtime does not know is skipped.
     */
    private void suggestBindings() {
        boundProfiles = 0;

        // Meta / Oculus Touch, including Quest and Quest Pro controllers.
        boundProfiles += bind("/interaction_profiles/oculus/touch_controller",
                gripPoseAction, "/user/hand/left/input/grip/pose",
                gripPoseAction, "/user/hand/right/input/grip/pose",
                aimPoseAction, "/user/hand/left/input/aim/pose",
                aimPoseAction, "/user/hand/right/input/aim/pose",
                triggerAction, "/user/hand/left/input/trigger/value",
                triggerAction, "/user/hand/right/input/trigger/value",
                gripAction, "/user/hand/left/input/squeeze/value",
                gripAction, "/user/hand/right/input/squeeze/value",
                primaryAction, "/user/hand/left/input/x/click",
                primaryAction, "/user/hand/right/input/a/click",
                secondaryAction, "/user/hand/left/input/y/click",
                secondaryAction, "/user/hand/right/input/b/click",
                menuAction, "/user/hand/left/input/menu/click",
                stickAction, "/user/hand/left/input/thumbstick",
                stickAction, "/user/hand/right/input/thumbstick",
                stickClickAction, "/user/hand/left/input/thumbstick/click",
                stickClickAction, "/user/hand/right/input/thumbstick/click",
                hapticAction, "/user/hand/left/output/haptic",
                hapticAction, "/user/hand/right/output/haptic");

        // Valve Index.
        boundProfiles += bind("/interaction_profiles/valve/index_controller",
                gripPoseAction, "/user/hand/left/input/grip/pose",
                gripPoseAction, "/user/hand/right/input/grip/pose",
                aimPoseAction, "/user/hand/left/input/aim/pose",
                aimPoseAction, "/user/hand/right/input/aim/pose",
                triggerAction, "/user/hand/left/input/trigger/value",
                triggerAction, "/user/hand/right/input/trigger/value",
                gripAction, "/user/hand/left/input/squeeze/value",
                gripAction, "/user/hand/right/input/squeeze/value",
                primaryAction, "/user/hand/left/input/a/click",
                primaryAction, "/user/hand/right/input/a/click",
                secondaryAction, "/user/hand/left/input/b/click",
                secondaryAction, "/user/hand/right/input/b/click",
                stickAction, "/user/hand/left/input/thumbstick",
                stickAction, "/user/hand/right/input/thumbstick",
                stickClickAction, "/user/hand/left/input/thumbstick/click",
                stickClickAction, "/user/hand/right/input/thumbstick/click",
                hapticAction, "/user/hand/left/output/haptic",
                hapticAction, "/user/hand/right/output/haptic");

        // HTC Vive wands. The trackpad stands in for a thumbstick.
        boundProfiles += bind("/interaction_profiles/htc/vive_controller",
                gripPoseAction, "/user/hand/left/input/grip/pose",
                gripPoseAction, "/user/hand/right/input/grip/pose",
                aimPoseAction, "/user/hand/left/input/aim/pose",
                aimPoseAction, "/user/hand/right/input/aim/pose",
                triggerAction, "/user/hand/left/input/trigger/value",
                triggerAction, "/user/hand/right/input/trigger/value",
                gripAction, "/user/hand/left/input/squeeze/click",
                gripAction, "/user/hand/right/input/squeeze/click",
                menuAction, "/user/hand/left/input/menu/click",
                menuAction, "/user/hand/right/input/menu/click",
                stickAction, "/user/hand/left/input/trackpad",
                stickAction, "/user/hand/right/input/trackpad",
                stickClickAction, "/user/hand/left/input/trackpad/click",
                stickClickAction, "/user/hand/right/input/trackpad/click",
                hapticAction, "/user/hand/left/output/haptic",
                hapticAction, "/user/hand/right/output/haptic");

        // The minimal controller every runtime must understand.
        boundProfiles += bind("/interaction_profiles/khr/simple_controller",
                gripPoseAction, "/user/hand/left/input/grip/pose",
                gripPoseAction, "/user/hand/right/input/grip/pose",
                aimPoseAction, "/user/hand/left/input/aim/pose",
                aimPoseAction, "/user/hand/right/input/aim/pose",
                triggerAction, "/user/hand/left/input/select/click",
                triggerAction, "/user/hand/right/input/select/click",
                menuAction, "/user/hand/left/input/menu/click",
                menuAction, "/user/hand/right/input/menu/click",
                hapticAction, "/user/hand/left/output/haptic",
                hapticAction, "/user/hand/right/output/haptic");

        if (boundProfiles == 0) {
            throw new OpenXrException("The OpenXR runtime accepted none of openxr4j's controller layouts.");
        }
    }

    /**
     * Suggests one controller type's layout. The arguments after the profile
     * alternate: an action, then the path of the control that feeds it.
     *
     * @return 1 if the runtime accepted the layout, 0 if it does not know this controller type
     */
    private int bind(String profile, Object... actionsAndPaths) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer path = stack.mallocLong(1);
            int n = actionsAndPaths.length / 2;
            XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.calloc(n, stack);
            for (int i = 0; i < n; i++) {
                bindings.get(i)
                        .action((XrAction) actionsAndPaths[i * 2])
                        .binding(path((String) actionsAndPaths[i * 2 + 1], path));
            }
            XrInteractionProfileSuggestedBinding suggestion = XrInteractionProfileSuggestedBinding.calloc(stack)
                    .type$Default()
                    .interactionProfile(path(profile, path))
                    .suggestedBindings(bindings);
            int result = XR10.xrSuggestInteractionProfileBindings(instance, suggestion);
            if (result == XR10.XR_ERROR_PATH_UNSUPPORTED) {
                return 0;
            }
            Results.check("Registering the layout for " + profile, result);
            return 1;
        }
    }

    private XrAction action(String name, String displayName, int type) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionCreateInfo info = XrActionCreateInfo.calloc(stack)
                    .type$Default()
                    .actionName(stack.UTF8(name))
                    .localizedActionName(stack.UTF8(displayName))
                    .actionType(type)
                    .subactionPaths(stack.longs(handPaths[0], handPaths[1]));
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Creating the input '" + name + "'", XR10.xrCreateAction(actionSet, info, handle));
            return new XrAction(handle.get(0), actionSet);
        }
    }

    private XrSpace actionSpace(XrAction poseAction, long handPath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionSpaceCreateInfo info = XrActionSpaceCreateInfo.calloc(stack)
                    .type$Default()
                    .action(poseAction)
                    .subactionPath(handPath)
                    .poseInActionSpace(VrSession::identity);
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Creating a hand space", XR10.xrCreateActionSpace(session, info, handle));
            return new XrSpace(handle.get(0), session);
        }
    }

    /**
     * Blocks until the runtime has started the session. The runtime starts it
     * by sending a short series of state changes; this waits for them rather
     * than leaving the caller to call update() until they have arrived.
     */
    private void waitUntilRunning() {
        long deadline = System.nanoTime() + START_TIMEOUT_NANOS;
        while (true) {
            pollEvents();
            if (running && focused) {
                return;
            }
            if (exitRequested) {
                throw new OpenXrException("The OpenXR runtime ended the session while it was starting.");
            }
            if (System.nanoTime() > deadline) {
                if (running) {
                    // Started, but the runtime has not given this session input focus.
                    // That is a state the caller can ask about, not a failure.
                    return;
                }
                throw new OpenXrException("The OpenXR runtime did not start the session within "
                        + (START_TIMEOUT_NANOS / 1_000_000_000L) + " seconds.");
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenXrException("Interrupted while waiting for the session to start.");
            }
        }
    }

    private void allocateReusableMemory() {
        eventBuffer = XrEventDataBuffer.calloc();
        activeActionSets = XrActiveActionSet.calloc(1);
        activeActionSets.get(0).actionSet(actionSet).subactionPath(XR10.XR_NULL_PATH);
        syncInfo = XrActionsSyncInfo.calloc()
                .type$Default()
                .activeActionSets(activeActionSets)
                .countActiveActionSets(1);
        getInfo = XrActionStateGetInfo.calloc().type$Default();
        floatState = XrActionStateFloat.calloc().type$Default();
        booleanState = XrActionStateBoolean.calloc().type$Default();
        vectorState = XrActionStateVector2f.calloc().type$Default();
        location = XrSpaceLocation.calloc().type$Default();
        hapticInfo = XrHapticActionInfo.calloc().type$Default().action(hapticAction);
        hapticVibration = XrHapticVibration.calloc().type$Default();
        timespec = memAlloc(16);
        timeOut = memAllocLong(1);
    }

    // ==================================================================
    // Per-frame internals
    // ==================================================================

    private void pollEvents() {
        while (true) {
            eventBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER).next(0L);
            int result = Results.check("Polling runtime events", XR10.xrPollEvent(instance, eventBuffer));
            if (result == XR10.XR_EVENT_UNAVAILABLE) {
                return;
            }
            int type = eventBuffer.type();
            if (type == XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                onSessionState(XrEventDataSessionStateChanged.create(eventBuffer.address()).state());
            } else if (type == XR10.XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED) {
                controllerTypesStale = true;
            } else if (type == XR10.XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                exitRequested = true;
            }
        }
    }

    private void onSessionState(int state) {
        if (state == XR10.XR_SESSION_STATE_READY) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                XrSessionBeginInfo beginInfo = XrSessionBeginInfo.calloc(stack)
                        .type$Default()
                        .primaryViewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);
                Results.check("Starting the session", XR10.xrBeginSession(session, beginInfo));
            }
            running = true;
        } else if (state == XR10.XR_SESSION_STATE_FOCUSED) {
            focused = true;
        } else if (state == XR10.XR_SESSION_STATE_VISIBLE || state == XR10.XR_SESSION_STATE_SYNCHRONIZED) {
            focused = false;
        } else if (state == XR10.XR_SESSION_STATE_STOPPING) {
            Results.check("Stopping the session", XR10.xrEndSession(session));
            running = false;
            focused = false;
        } else if (state == XR10.XR_SESSION_STATE_EXITING || state == XR10.XR_SESSION_STATE_LOSS_PENDING) {
            exitRequested = true;
        }
    }

    private void readHand(Hand hand, long now) {
        int i = hand.ordinal();
        HandState state = hands[i];
        getInfo.subactionPath(handPaths[i]);

        state.trigger = readFloat(triggerAction);
        state.grip = readFloat(gripAction);
        state.primary = readBoolean(primaryAction);
        state.secondary = readBoolean(secondaryAction);
        state.menu = readBoolean(menuAction);
        state.stickClick = readBoolean(stickClickAction);

        getInfo.action(stickAction);
        Results.check("Reading the thumbstick", XR10.xrGetActionStateVector2f(session, getInfo, vectorState));
        if (vectorState.isActive()) {
            state.stickX = vectorState.currentState().x();
            state.stickY = vectorState.currentState().y();
        } else {
            state.stickX = 0f;
            state.stickY = 0f;
        }

        boolean gripValid = locate(gripSpaces[i], now, state.gripPose);
        locate(aimSpaces[i], now, state.aimPose);
        state.tracked = gripValid;
    }

    private float readFloat(XrAction action) {
        getInfo.action(action);
        Results.check("Reading an input", XR10.xrGetActionStateFloat(session, getInfo, floatState));
        return floatState.isActive() ? floatState.currentState() : 0f;
    }

    private boolean readBoolean(XrAction action) {
        getInfo.action(action);
        Results.check("Reading an input", XR10.xrGetActionStateBoolean(session, getInfo, booleanState));
        return booleanState.isActive() && booleanState.currentState();
    }

    /** Fills {@code dest} if the space has a valid pose right now. Leaves it untouched otherwise. */
    private boolean locate(XrSpace space, long time, Pose dest) {
        return locate(space, baseSpace, time, dest);
    }

    private boolean locate(XrSpace space, XrSpace in, long time, Pose dest) {
        Results.check("Locating a space", XR10.xrLocateSpace(space, in, time, location));
        if ((location.locationFlags() & LOCATION_VALID) != LOCATION_VALID) {
            return false;
        }
        XrPosef pose = location.pose();
        dest.set(pose.position$().x(), pose.position$().y(), pose.position$().z(),
                pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w());
        return true;
    }

    /** The current moment, in the runtime's own clock. */
    private long now() {
        if (!hasTimespec) {
            if (display != null && display.displayTime() != 0L) {
                return display.displayTime();
            }
            throw new OpenXrException("This runtime cannot convert the system clock; call beginFrame() first.");
        }
        // System.nanoTime() on Linux reads CLOCK_MONOTONIC, which is the clock this conversion expects.
        long nanos = System.nanoTime();
        timespec.putLong(0, nanos / 1_000_000_000L);
        timespec.putLong(8, nanos % 1_000_000_000L);
        Results.check("Reading the runtime clock",
                KHRConvertTimespecTime.xrConvertTimespecTimeToTimeKHR(instance, memAddress(timespec), timeOut));
        return timeOut.get(0);
    }

    private void refreshControllerTypes() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrInteractionProfileState state = XrInteractionProfileState.calloc(stack).type$Default();
            for (Hand hand : Hand.values()) {
                int i = hand.ordinal();
                Results.check("Asking which controller a hand holds",
                        XR10.xrGetCurrentInteractionProfile(session, handPaths[i], state));
                controllerTypes[i] = pathToString(state.interactionProfile(), stack);
            }
        }
        controllerTypesStale = false;
    }

    private String pathToString(long path, MemoryStack stack) {
        if (path == XR10.XR_NULL_PATH) {
            return "";
        }
        IntBuffer length = stack.mallocInt(1);
        Results.check("Measuring a path", XR10.xrPathToString(instance, path, length, null));
        ByteBuffer text = stack.malloc(length.get(0));
        Results.check("Reading a path", XR10.xrPathToString(instance, path, length, text));
        return org.lwjgl.system.MemoryUtil.memUTF8(text, length.get(0) - 1);
    }

    private void clearHands() {
        hands[0].clearInput();
        hands[1].clearInput();
    }

    /** Lets the display helper reach the system id of the session it serves. */
    static long systemIdOf(XrSession session) {
        return SYSTEM_IDS.getOrDefault(session.address(), 0L);
    }

    private static final java.util.Map<Long, Long> SYSTEM_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("This VrSession has been closed.");
        }
    }

    // ==================================================================
    // Small helpers
    // ==================================================================

    private long path(String text, LongBuffer scratch) {
        Results.check("Resolving the path " + text, XR10.xrStringToPath(instance, text, scratch));
        return scratch.get(0);
    }

    private static void identity(XrPosef pose) {
        pose.orientation(q -> q.x(0f).y(0f).z(0f).w(1f));
        pose.position$(p -> p.x(0f).y(0f).z(0f));
    }

    /** Trims text so its UTF-8 form fits in {@code maxBytes}. */
    private static String fit(String text, int maxBytes) {
        String result = text == null || text.isBlank() ? "openxr4j application" : text;
        while (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
