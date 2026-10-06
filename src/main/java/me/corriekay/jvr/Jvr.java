package me.corriekay.jvr;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.openxr.XR10;
import org.lwjgl.openxr.XrExtensionProperties;
import org.lwjgl.system.MemoryStack;

/**
 * Entry point to jvr: open a session with {@link #openSession(String)}, or
 * ask the OpenXR runtime questions before opening one.
 */
public final class Jvr {

    private Jvr() {
    }

    /**
     * The names of every optional feature ("extension") the installed OpenXR
     * runtime offers, for example {@code XR_MND_headless}.
     *
     * <p>This asks the runtime directly and needs no session and no headset.
     *
     * @throws JvrException if no OpenXR runtime is installed or it cannot be reached
     */
    public static List<String> availableExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            Results.check("Counting runtime extensions",
                    XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null));

            int n = count.get(0);
            if (n == 0) {
                return List.of();
            }

            XrExtensionProperties.Buffer properties = XrExtensionProperties.calloc(n, stack);
            for (int i = 0; i < n; i++) {
                properties.get(i).type(XR10.XR_TYPE_EXTENSION_PROPERTIES);
            }
            Results.check("Listing runtime extensions",
                    XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties));

            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < count.get(0); i++) {
                names.add(properties.get(i).extensionNameString());
            }
            Collections.sort(names);
            return Collections.unmodifiableList(names);
        }
    }

    /**
     * Connects to the installed OpenXR runtime and opens an input-only
     * session: controllers and haptics, with nothing drawn to the headset.
     * The same as {@link VrSession#open(String)}, offered here so the way in
     * is easy to find.
     *
     * @param applicationName the name the runtime shows for this program
     * @throws JvrException if there is no runtime, no connected headset, or the
     *                      runtime lacks a feature this kind of session needs
     */
    public static VrSession openSession(String applicationName) {
        return VrSession.open(applicationName);
    }

    /** Whether the installed OpenXR runtime offers the named extension. */
    public static boolean isExtensionAvailable(String extensionName) {
        return availableExtensions().contains(extensionName);
    }
}
