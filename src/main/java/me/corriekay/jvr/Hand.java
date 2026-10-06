package me.corriekay.jvr;

/** Which hand a controller is held in. */
public enum Hand {
    LEFT("/user/hand/left"),
    RIGHT("/user/hand/right");

    final String userPath;

    Hand(String userPath) {
        this.userPath = userPath;
    }
}
