package fi.alavesa.superbvehicles.seat;

import org.bukkit.Input;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The latest {@code PlayerInputEvent} state per player. The client sends an input packet only when a key changes, so
 * a held W produces ONE event; the physics reads this cache every tick, and the key stays held until the next event.
 */
public final class InputCache {
    private final Map<UUID, Input> last = new HashMap<>();

    public void put(UUID player, Input input) { if (input != null) last.put(player, input); }
    /** The last input of the player, or null when none was received (nothing held). */
    public Input get(UUID player) { return last.get(player); }
    public void forget(UUID player) { last.remove(player); }
    public int size() { return last.size(); }
}
