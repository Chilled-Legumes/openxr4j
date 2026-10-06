package me.corriekay.openxr4j;

/**
 * Thrown when the OpenXR runtime reports a failure.
 *
 * <p>OpenXR itself reports failure through numeric result codes. openxr4j turns
 * every failing code into this exception, so callers never check codes by
 * hand.
 */
public class OpenXrException extends RuntimeException {

    private final int resultCode;
    private final String resultName;

    OpenXrException(String what, int resultCode) {
        super(what + " failed: " + Results.name(resultCode) + " (" + resultCode + ")");
        this.resultCode = resultCode;
        this.resultName = Results.name(resultCode);
    }

    OpenXrException(String message) {
        super(message);
        this.resultCode = 0;
        this.resultName = "";
    }

    /** The raw OpenXR result code, or 0 if this did not come from a result code. */
    public int resultCode() {
        return resultCode;
    }

    /** The OpenXR name of the result code, such as {@code XR_ERROR_RUNTIME_FAILURE}. */
    public String resultName() {
        return resultName;
    }
}
