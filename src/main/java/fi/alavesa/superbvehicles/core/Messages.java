package fi.alavesa.superbvehicles.core;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** messages.yml (MiniMessage) with the jar's defaults filled in for missing keys. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private YamlConfiguration cfg = new YamlConfiguration(), defaults = new YamlConfiguration();
    private final SuperbVehiclesPluginRef ref;

    /** Indirection so Messages can be unit-tested without a plugin. */
    public interface SuperbVehiclesPluginRef { File dataFolder(); java.io.InputStream resource(String name); java.util.logging.Logger logger(); }

    public Messages(SuperbVehiclesPluginRef ref) { this.ref = ref; }

    public void load() {
        try (var in = ref.resource("messages.yml")) { if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)); } catch (Exception e) { ref.logger().warning("messages.yml defaults missing from the jar: " + e); }
        File f = new File(ref.dataFolder(), "messages.yml");
        if (!f.exists()) { try { defaults.save(f); } catch (Exception e) { ref.logger().warning("could not write messages.yml: " + e); } }
        cfg = YamlConfiguration.loadConfiguration(f);
        boolean added = false;
        for (String k : defaults.getKeys(true)) if (!defaults.isConfigurationSection(k) && !cfg.contains(k)) { cfg.set(k, defaults.get(k)); added = true; }
        if (added) try { cfg.save(f); } catch (Exception ignored) { }
    }

    public String raw(String key) { String s = cfg.getString(key); if (s == null) s = defaults.getString(key); return s == null ? "<red>[missing message " + key + "]" : s; }

    public Component get(String key, TagResolver... resolvers) {
        String prefix = cfg.getString("prefix", defaults.getString("prefix", ""));
        List<TagResolver> all = new ArrayList<>(List.of(resolvers));
        all.add(Placeholder.parsed("prefix", prefix));
        return MM.deserialize(raw(key), TagResolver.resolver(all));
    }

    public Component parse(String mini, TagResolver... resolvers) { return MM.deserialize(mini == null ? "" : mini, TagResolver.resolver(resolvers)); }

    public void send(Audience to, String key, TagResolver... resolvers) { if (to != null) to.sendMessage(get(key, resolvers)); }
    public void actionBar(Audience to, String key, TagResolver... resolvers) { if (to != null) to.sendActionBar(get(key, resolvers)); }

    public static TagResolver str(String name, String value) { return Placeholder.unparsed(name, value == null ? "" : value); }
    public static TagResolver num(String name, double value) { return Placeholder.unparsed(name, Math.abs(value - Math.rint(value)) < 1e-9 ? String.valueOf((long) Math.rint(value)) : String.format(java.util.Locale.ROOT, "%.1f", value)); }
    public static TagResolver comp(String name, Component c) { return Placeholder.component(name, c); }
}
