package me.corriekay.jvr;

import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glRenderbufferStorage;
import static org.lwjgl.opengl.GL30.glViewport;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.KHROpenGLEnable;
import org.lwjgl.openxr.XR10;
import org.lwjgl.openxr.XrCompositionLayerProjection;
import org.lwjgl.openxr.XrCompositionLayerProjectionView;
import org.lwjgl.openxr.XrFovf;
import org.lwjgl.openxr.XrFrameBeginInfo;
import org.lwjgl.openxr.XrFrameEndInfo;
import org.lwjgl.openxr.XrFrameState;
import org.lwjgl.openxr.XrFrameWaitInfo;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSwapchain;
import org.lwjgl.openxr.XrSwapchainCreateInfo;
import org.lwjgl.openxr.XrSwapchainImageAcquireInfo;
import org.lwjgl.openxr.XrSwapchainImageOpenGLKHR;
import org.lwjgl.openxr.XrSwapchainImageReleaseInfo;
import org.lwjgl.openxr.XrSwapchainImageWaitInfo;
import org.lwjgl.openxr.XrView;
import org.lwjgl.openxr.XrViewConfigurationView;
import org.lwjgl.openxr.XrViewLocateInfo;
import org.lwjgl.openxr.XrViewState;
import org.lwjgl.system.MemoryStack;

/**
 * The headset's two eye images and the per-frame dance around them. Owned
 * by a {@link VrSession} opened with a graphics context. Package-private:
 * nothing here is part of jvr's public API.
 */
final class GlDisplay {

    static final int EYES = 2;

    private static final int GL_SRGB8_ALPHA8 = 0x8C43;
    private static final int GL_RGBA8 = 0x8058;

    private final XrInstance instance;
    private final XrSession session;
    private final XrSpace baseSpace;

    final int[] width = new int[EYES];
    final int[] height = new int[EYES];
    final int colourFormat;

    private final XrSwapchain[] swapchains = new XrSwapchain[EYES];
    private final int[][] framebuffers = new int[EYES][];
    private final int[] depthBuffers = new int[EYES];
    private final int[] acquiredIndex = {-1, -1};

    // Per-frame state, reused every frame.
    private final XrFrameWaitInfo frameWaitInfo = XrFrameWaitInfo.calloc().type$Default();
    private final XrFrameState frameState = XrFrameState.calloc().type$Default();
    private final XrFrameBeginInfo frameBeginInfo = XrFrameBeginInfo.calloc().type$Default();
    private final XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.calloc().type$Default();
    private final XrViewState viewState = XrViewState.calloc().type$Default();
    private final XrView.Buffer views = XrView.calloc(EYES);
    private final XrCompositionLayerProjectionView.Buffer layerViews = XrCompositionLayerProjectionView.calloc(EYES);
    private final XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc().type$Default();
    private final PointerBuffer layers = org.lwjgl.system.MemoryUtil.memAllocPointer(1);
    private final XrFrameEndInfo frameEndInfo = XrFrameEndInfo.calloc().type$Default();
    private final XrSwapchainImageAcquireInfo acquireInfo = XrSwapchainImageAcquireInfo.calloc().type$Default();
    private final XrSwapchainImageWaitInfo waitInfo = XrSwapchainImageWaitInfo.calloc().type$Default()
            .timeout(XR10.XR_INFINITE_DURATION);
    private final XrSwapchainImageReleaseInfo releaseInfo = XrSwapchainImageReleaseInfo.calloc().type$Default();
    private final IntBuffer scratchInt = org.lwjgl.system.MemoryUtil.memAllocInt(1);

    private boolean frameOpen;
    private boolean viewsValid;
    private long displayTime;

    GlDisplay(XrInstance instance, XrSession session, XrSpace baseSpace) {
        this.instance = instance;
        this.session = session;
        this.baseSpace = baseSpace;

        for (int eye = 0; eye < EYES; eye++) {
            views.get(eye).type$Default();
            layerViews.get(eye).type$Default();
        }
        viewLocateInfo.viewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO).space(baseSpace);
        layer.space(baseSpace).views(layerViews);
        layers.put(0, layer.address());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            colourFormat = chooseFormat(stack);
            createSwapchains(stack);
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private int chooseFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        Results.check("Counting image formats", XR10.xrEnumerateSwapchainFormats(session, count, null));
        LongBuffer formats = stack.mallocLong(count.get(0));
        Results.check("Listing image formats", XR10.xrEnumerateSwapchainFormats(session, count, formats));

        // Prefer sRGB, which is what SteamVR on Linux offers and what the headset
        // expects. Fall back to plain RGBA8, then to whatever comes first.
        boolean srgb = false;
        boolean rgba8 = false;
        for (int i = 0; i < count.get(0); i++) {
            srgb |= formats.get(i) == GL_SRGB8_ALPHA8;
            rgba8 |= formats.get(i) == GL_RGBA8;
        }
        if (srgb) {
            return GL_SRGB8_ALPHA8;
        }
        if (rgba8) {
            return GL_RGBA8;
        }
        if (count.get(0) == 0) {
            throw new JvrException("The OpenXR runtime offers no image formats for OpenGL.");
        }
        return (int) formats.get(0);
    }

    private void createSwapchains(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        Results.check("Counting views", XR10.xrEnumerateViewConfigurationViews(instance, VrSession.systemIdOf(session),
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null));
        if (count.get(0) != EYES) {
            throw new JvrException("Expected a two-eye headset, but the runtime reports " + count.get(0) + " views.");
        }
        XrViewConfigurationView.Buffer configViews = XrViewConfigurationView.calloc(EYES, stack);
        for (int eye = 0; eye < EYES; eye++) {
            configViews.get(eye).type$Default();
        }
        Results.check("Reading view sizes", XR10.xrEnumerateViewConfigurationViews(instance,
                VrSession.systemIdOf(session), XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, configViews));

        for (int eye = 0; eye < EYES; eye++) {
            width[eye] = configViews.get(eye).recommendedImageRectWidth();
            height[eye] = configViews.get(eye).recommendedImageRectHeight();

            XrSwapchainCreateInfo info = XrSwapchainCreateInfo.calloc(stack)
                    .type$Default()
                    .usageFlags(XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR10.XR_SWAPCHAIN_USAGE_SAMPLED_BIT)
                    .format(colourFormat)
                    .sampleCount(1)
                    .width(width[eye])
                    .height(height[eye])
                    .faceCount(1)
                    .arraySize(1)
                    .mipCount(1);
            PointerBuffer handle = stack.mallocPointer(1);
            Results.check("Creating an eye's image chain", XR10.xrCreateSwapchain(session, info, handle));
            swapchains[eye] = new XrSwapchain(handle.get(0), session);

            IntBuffer imageCount = stack.mallocInt(1);
            Results.check("Counting an eye's images",
                    XR10.xrEnumerateSwapchainImages(swapchains[eye], imageCount, null));
            XrSwapchainImageOpenGLKHR.Buffer images = XrSwapchainImageOpenGLKHR.calloc(imageCount.get(0), stack);
            for (int i = 0; i < imageCount.get(0); i++) {
                images.get(i).type$Default();
            }
            Results.check("Listing an eye's images", XR10.xrEnumerateSwapchainImages(swapchains[eye], imageCount,
                    org.lwjgl.openxr.XrSwapchainImageBaseHeader.create(images.address(), imageCount.get(0))));

            // One depth buffer per eye is enough: only one image per eye is ever in use at a time.
            depthBuffers[eye] = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthBuffers[eye]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width[eye], height[eye]);

            framebuffers[eye] = new int[imageCount.get(0)];
            for (int i = 0; i < imageCount.get(0); i++) {
                int fbo = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images.get(i).image(), 0);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBuffers[eye]);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    throw new JvrException("An eye framebuffer is incomplete (OpenGL status " + status + ").");
                }
                framebuffers[eye][i] = fbo;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            final int e = eye;
            layerViews.get(eye).subImage(sub -> sub
                    .swapchain(swapchains[e])
                    .imageRect(rect -> rect
                            .offset(o -> o.x(0).y(0))
                            .extent(ext -> ext.width(width[e]).height(height[e])))
                    .imageArrayIndex(0));
        }
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    /** Waits for the headset's next frame slot and starts it. Returns whether anything should be drawn. */
    boolean beginFrame() {
        if (frameOpen) {
            throw new IllegalStateException("beginFrame() called twice without endFrame().");
        }
        Results.check("Waiting for the next frame", XR10.xrWaitFrame(session, frameWaitInfo, frameState));
        displayTime = frameState.predictedDisplayTime();
        Results.check("Starting the frame", XR10.xrBeginFrame(session, frameBeginInfo));
        frameOpen = true;

        viewLocateInfo.displayTime(displayTime);
        Results.check("Locating the eyes", XR10.xrLocateViews(session, viewLocateInfo, viewState, scratchInt, views));
        long needed = XR10.XR_VIEW_STATE_POSITION_VALID_BIT | XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT;
        viewsValid = (viewState.viewStateFlags() & needed) == needed;
        return frameState.shouldRender() && viewsValid;
    }

    /** Makes an eye's image the current draw target and returns its framebuffer id. */
    int bindEye(int eye) {
        if (!frameOpen) {
            throw new IllegalStateException("bindEye() called outside a frame.");
        }
        if (acquiredIndex[eye] < 0) {
            Results.check("Acquiring an eye's image",
                    XR10.xrAcquireSwapchainImage(swapchains[eye], acquireInfo, scratchInt));
            acquiredIndex[eye] = scratchInt.get(0);
            Results.check("Waiting for an eye's image", XR10.xrWaitSwapchainImage(swapchains[eye], waitInfo));
        }
        int fbo = framebuffers[eye][acquiredIndex[eye]];
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width[eye], height[eye]);
        return fbo;
    }

    /** Hands whatever was drawn to the headset and closes the frame. */
    void endFrame() {
        if (!frameOpen) {
            return;
        }
        int drawn = 0;
        for (int eye = 0; eye < EYES; eye++) {
            if (acquiredIndex[eye] >= 0) {
                Results.check("Releasing an eye's image", XR10.xrReleaseSwapchainImage(swapchains[eye], releaseInfo));
                acquiredIndex[eye] = -1;
                drawn++;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        boolean submit = drawn == EYES && viewsValid;
        if (submit) {
            for (int eye = 0; eye < EYES; eye++) {
                layerViews.get(eye).pose(views.get(eye).pose()).fov(views.get(eye).fov());
            }
        }
        frameEndInfo
                .displayTime(displayTime)
                .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                .layers(submit ? layers : null)
                .layerCount(submit ? 1 : 0);
        Results.check("Finishing the frame", XR10.xrEndFrame(session, frameEndInfo));
        frameOpen = false;
    }

    boolean isFrameOpen() {
        return frameOpen;
    }

    long displayTime() {
        return displayTime;
    }

    XrPosef eyePose(int eye) {
        return views.get(eye).pose();
    }

    XrFovf eyeFov(int eye) {
        return views.get(eye).fov();
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    void close() {
        for (int eye = 0; eye < EYES; eye++) {
            if (framebuffers[eye] != null) {
                glDeleteFramebuffers(framebuffers[eye]);
            }
            if (depthBuffers[eye] != 0) {
                glDeleteRenderbuffers(depthBuffers[eye]);
            }
            if (swapchains[eye] != null) {
                XR10.xrDestroySwapchain(swapchains[eye]);
            }
        }
        frameWaitInfo.free();
        frameState.free();
        frameBeginInfo.free();
        viewLocateInfo.free();
        viewState.free();
        views.free();
        layerViews.free();
        layer.free();
        org.lwjgl.system.MemoryUtil.memFree(layers);
        frameEndInfo.free();
        acquireInfo.free();
        waitInfo.free();
        releaseInfo.free();
        org.lwjgl.system.MemoryUtil.memFree(scratchInt);
    }
}
