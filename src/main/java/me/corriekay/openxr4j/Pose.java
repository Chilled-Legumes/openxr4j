package me.corriekay.openxr4j;

/**
 * A position and an orientation in space.
 *
 * <p>Position is in metres. Orientation is a unit quaternion. Axes follow
 * OpenXR: +X is right, +Y is up, -Z is forward.
 *
 * <p>A {@code Pose} is a plain reusable object. Methods that produce a pose
 * come in two forms: one that returns a new {@code Pose}, and one that fills
 * a {@code Pose} you pass in, for code that runs every frame and wants to
 * create no garbage.
 */
public final class Pose {

    private float x, y, z;
    private float qx, qy, qz;
    private float qw = 1f;

    /** Creates a pose at the origin with no rotation. */
    public Pose() {
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float z() {
        return z;
    }

    public float qx() {
        return qx;
    }

    public float qy() {
        return qy;
    }

    public float qz() {
        return qz;
    }

    public float qw() {
        return qw;
    }

    /** Copies another pose into this one and returns this. */
    public Pose set(Pose other) {
        return set(other.x, other.y, other.z, other.qx, other.qy, other.qz, other.qw);
    }

    /** Sets every component and returns this. */
    public Pose set(float x, float y, float z, float qx, float qy, float qz, float qw) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
        return this;
    }

    /**
     * A compact, readable form for logs and debugging, for example
     * {@code pos(0.208, -0.134, 0.299) rot(-0.954, -0.052, 0.286, 0.067)}.
     * Position is x, y, z in metres; rotation is the quaternion's x, y, z, w.
     */
    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "pos(%.3f, %.3f, %.3f) rot(%.3f, %.3f, %.3f, %.3f)", x, y, z, qx, qy, qz, qw);
    }
}
