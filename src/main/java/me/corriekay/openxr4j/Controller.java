package me.corriekay.openxr4j;

/**
 * One hand's controller, as a long-lived object. Take it once with
 * {@link VrSession#controller(Hand)} and keep it: every read goes to the
 * session's live state, which {@code beginFrame()} (or {@code update()})
 * refreshes, so it is never stale and never needs fetching again.
 *
 * <p>Each method here is the same as the {@code VrSession} method of the
 * same name with the hand filled in.
 */
public final class Controller {

    private final VrSession session;
    private final Hand hand;

    Controller(VrSession session, Hand hand) {
        this.session = session;
        this.hand = hand;
    }

    /** Which hand this is. */
    public Hand hand() {
        return hand;
    }

    /** Whether the controller currently has a valid position and orientation. */
    public boolean isTracked() {
        return session.isTracked(hand);
    }

    /** Where the hand is holding the controller. Returns a new {@link Pose}. */
    public Pose gripPose() {
        return session.gripPose(hand);
    }

    /** Fills {@code dest} with the grip pose and returns it. Creates no garbage. */
    public Pose gripPose(Pose dest) {
        return session.gripPose(hand, dest);
    }

    /** Where the controller is pointing (-Z is the ray). Returns a new {@link Pose}. */
    public Pose aimPose() {
        return session.aimPose(hand);
    }

    /** Fills {@code dest} with the aim pose and returns it. Creates no garbage. */
    public Pose aimPose(Pose dest) {
        return session.aimPose(hand, dest);
    }

    /** How far the index-finger trigger is pulled, 0 to 1. */
    public float trigger() {
        return session.trigger(hand);
    }

    /** How hard the grip is squeezed, 0 to 1. */
    public float grip() {
        return session.grip(hand);
    }

    /** The lower face button (A or X). */
    public boolean primaryButton() {
        return session.primaryButton(hand);
    }

    /** The upper face button (B or Y). */
    public boolean secondaryButton() {
        return session.secondaryButton(hand);
    }

    /** The menu button; see {@link VrSession#menuButton(Hand)} for why it rarely arrives. */
    public boolean menuButton() {
        return session.menuButton(hand);
    }

    /** Thumbstick left-right, -1 to 1. */
    public float thumbstickX() {
        return session.thumbstickX(hand);
    }

    /** Thumbstick down-up, -1 to 1. */
    public float thumbstickY() {
        return session.thumbstickY(hand);
    }

    /** Whether the thumbstick is clicked in. */
    public boolean thumbstickPressed() {
        return session.thumbstickPressed(hand);
    }

    /** Continuous vibration at {@code strength} (0 to 1) until {@link #stopVibration()}. */
    public void vibrate(float strength) {
        session.vibrate(hand, strength);
    }

    /** Vibration at {@code strength} for {@code seconds}. */
    public void vibrate(float strength, float seconds) {
        session.vibrate(hand, strength, seconds);
    }

    /** Vibration at {@code strength} for {@code seconds} at {@code frequencyHz}. */
    public void vibrate(float strength, float seconds, float frequencyHz) {
        session.vibrate(hand, strength, seconds, frequencyHz);
    }

    /** Stops any vibration. */
    public void stopVibration() {
        session.stopVibration(hand);
    }

    /** The controller type the runtime reports for this hand, e.g. "oculus touch". */
    public String type() {
        return session.controllerType(hand);
    }

    @Override
    public String toString() {
        return hand.name().toLowerCase(java.util.Locale.ROOT) + " controller (" + type() + ")";
    }
}
