package me.corriekay.openxr4j;

import static org.lwjgl.opengl.GL32.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL32.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL32.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL32.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL32.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL32.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL32.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL32.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL32.GL_LINEAR;
import static org.lwjgl.opengl.GL32.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL32.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL32.GL_RGBA;
import static org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL32.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL32.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL32.GL_TIMEOUT_IGNORED;
import static org.lwjgl.opengl.GL32.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL32.glBindFramebuffer;
import static org.lwjgl.opengl.GL32.glBindRenderbuffer;
import static org.lwjgl.opengl.GL32.glBindTexture;
import static org.lwjgl.opengl.GL32.glBlitFramebuffer;
import static org.lwjgl.opengl.GL32.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL32.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL32.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL32.glDeleteSync;
import static org.lwjgl.opengl.GL32.glDeleteTextures;
import static org.lwjgl.opengl.GL32.glEnable;
import static org.lwjgl.opengl.GL32.glFenceSync;
import static org.lwjgl.opengl.GL32.glFinish;
import static org.lwjgl.opengl.GL32.glFlush;
import static org.lwjgl.opengl.GL32.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL32.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL32.glGenFramebuffers;
import static org.lwjgl.opengl.GL32.glGenRenderbuffers;
import static org.lwjgl.opengl.GL32.glGenTextures;
import static org.lwjgl.opengl.GL32.glIsTexture;
import static org.lwjgl.opengl.GL32.glRenderbufferStorage;
import static org.lwjgl.opengl.GL32.glTexImage2D;
import static org.lwjgl.opengl.GL32.glTexParameteri;
import static org.lwjgl.opengl.GL32.glViewport;
import static org.lwjgl.opengl.GL32.glWaitSync;

import java.nio.ByteBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A desktop window showing a third view of the scene, beside the headset's
 * two eyes. Owned by a {@link VrSession}. Package-private: the public face is
 * the spectator {@link Eye} and {@code VrSession.addSpectator}.
 *
 * <p>The view is drawn in the session's own OpenGL context, into a picture
 * (a texture) that belongs to this class, the same way the eyes are. Only at
 * the end of the frame does this class switch to the window's context and
 * copy the picture onto the window. The reason: contexts that share each
 * other's objects share buffers, textures and shaders, but not vertex array
 * objects or framebuffers, so a program's meshes would not draw in the
 * window's own context.
 */
final class Spectator {

    private static final int GL_SRGB8_ALPHA8 = 0x8C43;

    private final long sessionWindow;
    final long window;
    final boolean ownsWindow;
    /** The eye whose pose and field of view this view borrows: {@link Eye#LEFT} or {@link Eye#RIGHT}. */
    final int follows;
    private final int colourFormat;

    int width;
    int height;
    private final int[] sizeX = new int[1];
    private final int[] sizeY = new int[1];

    // In the session's context.
    private int texture;
    private int depthBuffer;
    private int drawFramebuffer;

    // In the window's context. Framebuffers are not shared, so the copy needs one of its own there.
    private int readFramebuffer;
    private GLCapabilities windowCapabilities;

    private boolean drawn;

    /** Call with the session's context current. Leaves it current. */
    Spectator(long sessionWindow, long window, boolean ownsWindow, int follows, int colourFormat) {
        this.sessionWindow = sessionWindow;
        this.window = window;
        this.ownsWindow = ownsWindow;
        this.follows = follows;
        this.colourFormat = colourFormat;

        GLFW.glfwGetFramebufferSize(window, sizeX, sizeY);
        width = Math.max(1, sizeX[0]);
        height = Math.max(1, sizeY[0]);

        texture = glGenTextures();
        depthBuffer = glGenRenderbuffers();
        allocate();
        drawFramebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, drawFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            deleteSessionObjects();
            throw new OpenXrException("The spectator's framebuffer is incomplete (OpenGL status " + status + ").");
        }
        // The window's context only sees the texture once its creation has finished here.
        glFinish();

        try {
            attachWindow();
        } catch (RuntimeException e) {
            deleteSessionObjects();
            throw e;
        }
    }

    /** Sets up the window's side: its own framebuffer for the copy, and no waiting for the monitor. */
    private void attachWindow() {
        GLCapabilities sessionCapabilities = GL.getCapabilities();
        GLFW.glfwMakeContextCurrent(window);
        try {
            windowCapabilities = GL.createCapabilities();
            if (!glIsTexture(texture)) {
                throw new OpenXrException("The spectator window's OpenGL context does not share the session's,"
                        + " so the session's picture cannot be copied onto it. Make the window with"
                        + " vr.createWindow(...), or pass vr.window() as the share argument of glfwCreateWindow.");
            }
            // Waiting for the monitor on every swap would hold the headset to the monitor's rate.
            GLFW.glfwSwapInterval(0);
            readFramebuffer = glGenFramebuffers();
        } finally {
            GLFW.glfwMakeContextCurrent(sessionWindow);
            GL.setCapabilities(sessionCapabilities);
        }
    }

    /** (Re)sizes the picture and its depth buffer to {@link #width} by {@link #height}. Session context. */
    private void allocate() {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, colourFormat, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    /**
     * Matches the picture to the window's current size. Called at the start
     * of each frame, so the size and the projection agree for the whole
     * frame. A minimised window (size 0) keeps the last size.
     */
    void refreshSize() {
        GLFW.glfwGetFramebufferSize(window, sizeX, sizeY);
        if (sizeX[0] > 0 && sizeY[0] > 0 && (sizeX[0] != width || sizeY[0] != height)) {
            width = sizeX[0];
            height = sizeY[0];
            allocate();
        }
    }

    /** Whether the user has asked to close the window (its X button). */
    boolean closeRequested() {
        return GLFW.glfwWindowShouldClose(window);
    }

    /** Makes the picture the current draw target, like an eye's. Session context. */
    int bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, drawFramebuffer);
        glViewport(0, 0, width, height);
        if (colourFormat == GL_SRGB8_ALPHA8) {
            glEnable(GL_FRAMEBUFFER_SRGB); // switched off again by GlDisplay.endFrame, as for the eyes
        }
        drawn = true;
        return drawFramebuffer;
    }

    /**
     * Copies the picture onto the window and shows it, if anything was drawn
     * this frame. Call with the session's context current, after the frame
     * has gone to the headset. Leaves the session's context current.
     */
    void present() {
        if (!drawn) {
            return;
        }
        drawn = false;

        // The window's context may only read the picture once the drawing into it has finished.
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();

        GLCapabilities sessionCapabilities = GL.getCapabilities();
        GLFW.glfwMakeContextCurrent(window);
        GL.setCapabilities(windowCapabilities);
        try {
            glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
            glDeleteSync(fence);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            // Attached afresh every frame: a context is only promised to see another
            // context's changes to a texture when it attaches that texture again.
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            GLFW.glfwGetFramebufferSize(window, sizeX, sizeY);
            // sRGB encoding is off in this context, so the copy moves the stored values
            // unchanged, and the stored values are already what a monitor expects.
            glBlitFramebuffer(0, 0, width, height, 0, 0, sizeX[0], sizeY[0], GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            GLFW.glfwSwapBuffers(window);
        } finally {
            GLFW.glfwMakeContextCurrent(sessionWindow);
            GL.setCapabilities(sessionCapabilities);
        }
    }

    /**
     * Frees everything, and destroys the window if openxr4j made it. A window
     * the program handed in is left open. Call with the session's context
     * current, before the window is destroyed.
     */
    void close() {
        if (readFramebuffer != 0) {
            GLCapabilities sessionCapabilities = GL.getCapabilities();
            GLFW.glfwMakeContextCurrent(window);
            GL.setCapabilities(windowCapabilities);
            glDeleteFramebuffers(readFramebuffer);
            readFramebuffer = 0;
            GLFW.glfwMakeContextCurrent(sessionWindow);
            GL.setCapabilities(sessionCapabilities);
        }
        deleteSessionObjects();
        if (ownsWindow) {
            GLFW.glfwDestroyWindow(window);
        }
    }

    private void deleteSessionObjects() {
        if (drawFramebuffer != 0) {
            glDeleteFramebuffers(drawFramebuffer);
            drawFramebuffer = 0;
        }
        if (depthBuffer != 0) {
            glDeleteRenderbuffers(depthBuffer);
            depthBuffer = 0;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
    }
}
