package fi.alavesa.superbvehicles.physics;

import org.bukkit.Input;

/** The driver's input this tick: held keys (from PlayerInputEvent), look direction and one-shot key events. */
public final class Controls {
    public boolean present;                     // a driver is seated
    public boolean forward, backward, left, right, jump, sneak, sprint;
    public boolean jumpPressed, sprintPressed, swapHands, drop;   // edges this tick
    public int hotbar = -1;                     // slot selected this tick (0..8), -1 none
    public float lookYaw, lookPitch;            // driver's head
    public boolean attack, use;                 // left / right click this tick

    public void clearEdges() { jumpPressed = false; sprintPressed = false; swapHands = false; drop = false; hotbar = -1; attack = false; use = false; }

    public void held(Input in) {
        if (in == null) { forward = backward = left = right = jump = sneak = sprint = false; return; }
        if (in.isJump() && !jump) jumpPressed = true;
        if (in.isSprint() && !sprint) sprintPressed = true;
        forward = in.isForward(); backward = in.isBackward(); left = in.isLeft(); right = in.isRight(); jump = in.isJump(); sneak = in.isSneak(); sprint = in.isSprint();
    }

    public double throttle() { return (forward ? 1 : 0) - (backward ? 1 : 0); }
    public double steer() { return (right ? 1 : 0) - (left ? 1 : 0); }
    public boolean any() { return forward || backward || left || right || jump || sprint; }

    /** Is the named key (config controls table) held? */
    public boolean key(String name) {
        if (name == null) return false;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> forward; case "backward" -> backward; case "left" -> left; case "right" -> right;
            case "jump", "space" -> jump; case "sneak", "shift" -> sneak; case "sprint", "ctrl" -> sprint;
            case "swap_hands", "f" -> swapHands; case "drop", "q" -> drop; case "attack", "left_click" -> attack; case "use", "right_click" -> use;
            default -> false;
        };
    }
    /** One-shot press of the named key this tick. */
    public boolean pressed(String name) {
        if (name == null) return false;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "jump", "space" -> jumpPressed; case "sprint", "ctrl" -> sprintPressed; case "swap_hands", "f" -> swapHands; case "drop", "q" -> drop;
            case "attack", "left_click" -> attack; case "use", "right_click" -> use;
            default -> false;
        };
    }
}
