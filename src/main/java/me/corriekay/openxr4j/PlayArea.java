package me.corriekay.openxr4j;

import java.util.Locale;

/**
 * The floor rectangle the user traced in room setup, in the same space as the
 * hands and head (origin at the head's starting spot). The rectangle is
 * centred on ({@code centreX}, {@code centreZ}) and turned {@code yawDegrees}
 * about the vertical axis; {@code width} runs across it (its x) and
 * {@code depth} front to back (its z), both in metres. A runtime that knows
 * the floor but not the rectangle reports a width and depth of 0.
 */
public record PlayArea(float centreX, float centreZ, float yawDegrees, float width, float depth) {

    /** Whether the runtime reported a rectangle at all. */
    public boolean hasBounds() {
        return width > 0f && depth > 0f;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "play area %.2f x %.2f m at (%.2f, %.2f) turned %.0f deg",
                width, depth, centreX, centreZ, yawDegrees);
    }
}
