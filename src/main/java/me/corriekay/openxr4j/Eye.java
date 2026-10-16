package me.corriekay.openxr4j;

/**
 * One of the headset's two eyes, or the spectator window: its picture to
 * draw into, and where it is and what it sees for the current frame.
 *
 * <p>Get them from {@link VrSession#eyes()}, {@link VrSession#leftEye()} and
 * {@link VrSession#rightEye()} on a drawing session, and use them inside a
 * frame:
 *
 * <pre>{@code
 * if (vr.beginFrame()) {
 *     for (Eye eye : vr.eyes()) {
 *         eye.draw(() -> scene.render(eye));
 *     }
 * }
 * vr.endFrame();
 * }</pre>
 *
 * <p>{@link #draw(Runnable)} points OpenGL at this eye's picture and runs your
 * drawing code. It does not point it anywhere afterward; {@code endFrame()}
 * returns OpenGL to the window once the frame is done.
 *
 * <p>A spectator ({@link VrSession#addSpectator()}) is a third {@code Eye}
 * that {@code eyes()} lists after the two real ones. It sees from one real
 * eye's pose and shows the picture on a desktop window, so the loop above
 * draws it with no change.
 */
public final class Eye {

    /** The left eye's index: for {@link VrSession#bindEye(int)} and the other per-eye calls, and for choosing which eye a spectator follows. */
    public static final int LEFT = 0;

    /** The right eye's index. See {@link #LEFT}. */
    public static final int RIGHT = 1;

    /** The spectator's index, when one is attached with {@link VrSession#addSpectator()}. */
    public static final int SPECTATOR = 2;

    private final VrSession session;
    private final int index;

    Eye(VrSession session, int index) {
        this.session = session;
        this.index = index;
    }

    /** {@link #LEFT}, {@link #RIGHT} or {@link #SPECTATOR}. */
    public int index() {
        return index;
    }

    /** Whether this is the left eye. */
    public boolean isLeft() {
        return index == LEFT;
    }

    /** Whether this is the spectator window rather than one of the headset's eyes. */
    public boolean isSpectator() {
        return index == SPECTATOR;
    }

    /** Width in pixels of this eye's picture. */
    public int width() {
        return session.eyeWidth(index);
    }

    /** Height in pixels of this eye's picture. */
    public int height() {
        return session.eyeHeight(index);
    }

    /**
     * Points OpenGL at this eye's picture, with the viewport covering it, and
     * runs {@code scene}. Call inside a frame that {@code beginFrame()} said
     * should be drawn.
     */
    public void draw(Runnable scene) {
        session.bindEye(index);
        try {
            scene.run();
        } finally {
            session.clearCurrentEye();
        }
    }

    /** Points OpenGL at this eye's picture and returns its framebuffer id, for code that would rather bind by hand. */
    public int bind() {
        return session.bindEye(index);
    }

    /** This eye's pose for the current frame. Returns a new {@link Pose}. */
    public Pose pose() {
        return session.eyePose(index);
    }

    /** Fills {@code dest} with this eye's pose for the current frame. Creates no garbage. */
    public Pose pose(Pose dest) {
        return session.eyePose(index, dest);
    }

    /** Fills {@code dest} (16 floats, column major) with this eye's view matrix. */
    public float[] viewMatrix(float[] dest) {
        return session.viewMatrix(index, dest);
    }

    /** Fills {@code dest} (16 floats, column major) with this eye's projection matrix. */
    public float[] projectionMatrix(float near, float far, float[] dest) {
        return session.projectionMatrix(index, near, far, dest);
    }

    /**
     * Fills {@code dest} (16 floats, column major) with projection times view
     * for this eye: the one matrix that takes a world position to this eye's
     * screen. {@code near} and {@code far} are the closest and farthest
     * distances, in metres, that will be drawn.
     */
    public float[] viewProjectionMatrix(float near, float far, float[] dest) {
        return session.viewProjectionMatrix(index, near, far, dest);
    }

    /** Projection matrix using the session's draw distances ({@code VrSession.setDrawDistances}). */
    public float[] projectionMatrix(float[] dest) {
        return session.projectionMatrix(index, dest);
    }

    /** Projection times view using the session's draw distances ({@code VrSession.setDrawDistances}). */
    public float[] viewProjectionMatrix(float[] dest) {
        return session.viewProjectionMatrix(index, dest);
    }

    @Override
    public String toString() {
        return switch (index) {
            case LEFT -> "left eye " + width() + "x" + height();
            case RIGHT -> "right eye " + width() + "x" + height();
            default -> "spectator " + width() + "x" + height() + ", following the "
                    + (session.spectatorFollows() == LEFT ? "left" : "right") + " eye";
        };
    }
}
