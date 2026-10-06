package me.corriekay.jvr;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.openxr.XR10;

/**
 * Turns OpenXR result codes into exceptions and readable names.
 *
 * <p>OpenXR result codes come in three kinds: zero is plain success,
 * positive values mean "succeeded, with something to note", and negative
 * values are errors. Only the negative ones are failures.
 */
final class Results {

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        for (Field field : XR10.class.getFields()) {
            String name = field.getName();
            boolean isResult = name.startsWith("XR_ERROR_")
                    || name.equals("XR_SUCCESS")
                    || name.equals("XR_TIMEOUT_EXPIRED")
                    || name.equals("XR_SESSION_LOSS_PENDING")
                    || name.equals("XR_EVENT_UNAVAILABLE")
                    || name.equals("XR_SPACE_BOUNDS_UNAVAILABLE")
                    || name.equals("XR_SESSION_NOT_FOCUSED")
                    || name.equals("XR_FRAME_DISCARDED");
            if (!isResult || field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                NAMES.put(field.getInt(null), name);
            } catch (IllegalAccessException e) {
                // A public static field we cannot read is not worth failing over.
            }
        }
    }

    private Results() {
    }

    /** Throws if {@code result} is an error. Returns it otherwise, so qualified successes can be inspected. */
    static int check(String what, int result) {
        if (result < 0) {
            throw new JvrException(what, result);
        }
        return result;
    }

    static String name(int result) {
        return NAMES.getOrDefault(result, "unknown result");
    }
}
