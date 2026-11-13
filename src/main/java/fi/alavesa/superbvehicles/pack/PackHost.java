package fi.alavesa.superbvehicles.pack;

import com.sun.net.httpserver.HttpServer;
import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.compat.SamePortHttp;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/**
 * Gets the pack to players, ADDED to whatever packs the server already sends, under a fixed id so a re-send
 * replaces only ours. pack.host.mode:
 *   same-port (default) - served on the Minecraft port itself, nothing to open (not behind Velocity/Bungee)
 *   self                - a built-in HTTP server on pack.host.port (must be reachable)
 *   url                 - you host the zip at pack.host.url
 *   folder              - the pack is unpacked into pack.host.folder (an existing pack's folder, another host sends it)
 */
public final class PackHost implements Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("fi.alavesa.superbvehicles.pack".getBytes(StandardCharsets.UTF_8));
    /** The shader HUD travels as a SECOND pack: if a client cannot use it, the vehicle models are unaffected. */
    public static final UUID HUD_PACK_ID = UUID.nameUUIDFromBytes("fi.alavesa.superbvehicles.hud".getBytes(StandardCharsets.UTF_8));
    private String hudSha1 = "";
    private final SuperbVehiclesPlugin plugin;
    private HttpServer server;
    private SamePortHttp samePort;
    private String sha1 = "";
    private String lastProblem = "";
    private long lastFailLog;

    public PackHost(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("pack.host.enabled", true); }
    public String mode() { String m = plugin.getConfig().getString("pack.host.mode", "same-port").toLowerCase(Locale.ROOT).trim(); return m.equals("auto") ? "same-port" : m; }
    public int port() { return plugin.getConfig().getInt("pack.host.port", 25580); }
    public File zip() { return new File(new File(plugin.getDataFolder(), "pack"), "SuperbVehicles-pack.zip"); }
    public File hudZip() { return new File(new File(plugin.getDataFolder(), "pack"), "SuperbVehicles-hud.zip"); }
    public String hudSha1() { return hudSha1; }
    /** Is a shader HUD pack built and deliverable in this mode? */
    public boolean hudAvailable() { return !hudSha1.isEmpty() && hudZip().isFile() && !mode().equals("folder") && (!mode().equals("url") || !plugin.getConfig().getString("pack.host.hud-url", "").isBlank()); }
    public String sha1() { return sha1; }

    public void start() {
        if (!enabled()) { plugin.getLogger().info("Pack host disabled (pack.host.enabled: false) - install pack/SuperbVehicles-pack.zip yourself."); return; }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refreshHash();
        switch (mode()) {
            case "same-port" -> startSamePort();
            case "self" -> startServer();
            case "url" -> plugin.getLogger().info("Pack host: url mode - players get " + plugin.getConfig().getString("pack.host.url", "") + " (re-upload pack/SuperbVehicles-pack.zip there after each /sbv pack rebuild).");
            case "folder" -> { String f = plugin.getConfig().getString("pack.host.folder", ""); plugin.getLogger().info("Pack host: folder mode - the pack is merged into " + f + " after each build; whoever hosts that folder sends it."); mergeIntoFolder(); }
            default -> plugin.getLogger().warning("pack.host.mode must be same-port, self, url or folder (is '" + mode() + "') - nothing is sent.");
        }
    }

    private void startSamePort() {
        if (samePort != null) return;
        SamePortHttp h = new SamePortHttp(plugin.getServer(), "superbvehicles", path -> path.contains("/superbvehicles/hud-") ? hudZip() : zip());
        String problem = h.install();
        if (problem != null) {
            lastProblem = "could not hook the Minecraft port for pack downloads (" + problem + ")";
            plugin.getLogger().severe("Pack host NOT started: " + lastProblem + " - set pack.host.mode: self (open a port) or url.");
            return;
        }
        samePort = h; lastProblem = "";
        plugin.getLogger().info("Pack host: same-port mode - the pack is served on the Minecraft port (" + plugin.getServer().getPort() + "). Nothing to open. (Behind a Velocity/Bungee proxy use pack.host.mode: self or url.)");
    }

    private void startServer() {
        if (server != null) return;
        if (port() == plugin.getServer().getPort()) { lastProblem = "pack.host.port " + port() + " is the Minecraft port - use pack.host.mode: same-port for that, or pick a free port."; plugin.getLogger().severe("Pack host NOT started: " + lastProblem); return; }
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port()), 8);
            server.createContext("/", ex -> {
                File f = ex.getRequestURI().getPath().contains("SuperbVehicles-hud") ? hudZip() : zip();
                if (!f.exists() || !ex.getRequestURI().getPath().endsWith(".zip")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                byte[] data = Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            });
            server.setExecutor(null);
            server.start();
            lastProblem = "";
            plugin.getLogger().info("Pack host: built-in server on port " + port() + " - this port must be open to players.");
        } catch (Exception e) {
            lastProblem = "port " + port() + " could not be opened: " + e.getMessage();
            plugin.getLogger().severe("Pack host NOT started: " + lastProblem);
            server = null;
        }
    }

    /** folder mode: unpack assets/ and pack.mcmeta-less content into the configured folder (ours only: assets/superbvehicles). */
    public void mergeIntoFolder() {
        String dir = plugin.getConfig().getString("pack.host.folder", "");
        if (dir == null || dir.isBlank()) { lastProblem = "pack.host.folder is empty"; plugin.getLogger().warning("Pack host: " + lastProblem); return; }
        File target = new File(dir);
        if (!target.isAbsolute()) target = new File(plugin.getDataFolder().getParentFile().getParentFile(), dir);
        File f = zip();
        if (!f.isFile()) return;
        try {
            File ours = new File(target, "assets/superbvehicles");
            if (ours.isDirectory()) try (var walk = Files.walk(ours.toPath())) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
            try (ZipInputStream in = new ZipInputStream(new java.io.FileInputStream(f))) {
                java.util.zip.ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().startsWith("assets/")) continue;
                    File out = new File(target, e.getName());
                    if (!out.toPath().normalize().startsWith(target.toPath().normalize())) continue;
                    out.getParentFile().mkdirs();
                    Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            lastProblem = "";
            plugin.getLogger().info("Pack host: merged the SuperbVehicles assets into " + target.getPath() + " (pack.mcmeta there is yours; it must declare min_format 97 or higher for 26.x clients).");
        } catch (Exception e) { lastProblem = "could not merge into " + target + ": " + e.getMessage(); plugin.getLogger().warning("Pack host: " + lastProblem); }
    }

    public void stop() { if (server != null) { server.stop(0); server = null; } if (samePort != null) { samePort.uninstall(); samePort = null; } }

    public void refreshHash() {
        try { File f = zip(); sha1 = f.exists() ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(f.toPath()))) : ""; } catch (Exception e) { sha1 = ""; }
        try { File f = hudZip(); hudSha1 = f.exists() ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(f.toPath()))) : ""; } catch (Exception e) { hudSha1 = ""; }
    }

    String addressFor(Player p) {
        String cfg = plugin.getConfig().getString("pack.host.address", "");
        if (cfg != null && !cfg.isBlank()) return cfg.trim();
        try { var vh = p.getVirtualHost(); if (vh != null && vh.getHostString() != null && !vh.getHostString().isBlank()) return vh.getHostString(); } catch (Throwable ignored) { }
        String ip = plugin.getServer().getIp();
        if (ip != null && !ip.isBlank()) return ip;
        try { return java.net.InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "localhost"; }
    }

    String hostPort(Player p) {
        String host = addressFor(p);
        int port = plugin.getServer().getPort();
        String cfg = plugin.getConfig().getString("pack.host.address", "");
        if (cfg == null || cfg.isBlank()) try { var vh = p.getVirtualHost(); if (vh != null && vh.getPort() > 0) port = vh.getPort(); } catch (Throwable ignored) { }
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host + ":" + port;
    }

    public String urlFor(Player p) {
        return switch (mode()) {
            case "same-port" -> samePort == null ? null : "http://" + hostPort(p) + "/superbvehicles/" + (sha1.isEmpty() ? "pack" : sha1) + ".zip";
            case "self" -> server == null ? null : "http://" + addressFor(p) + ":" + port() + "/SuperbVehicles-pack.zip?v=" + (sha1.isEmpty() ? "0" : sha1.substring(0, 12));
            case "url" -> { String u = plugin.getConfig().getString("pack.host.url", ""); yield u == null || u.isBlank() ? null : u.trim(); }
            default -> null;
        };
    }

    public String hudUrlFor(Player p) {
        return switch (mode()) {
            case "same-port" -> samePort == null ? null : "http://" + hostPort(p) + "/superbvehicles/hud-" + (hudSha1.isEmpty() ? "pack" : hudSha1) + ".zip";
            case "self" -> server == null ? null : "http://" + addressFor(p) + ":" + port() + "/SuperbVehicles-hud.zip?v=" + (hudSha1.isEmpty() ? "0" : hudSha1.substring(0, 12));
            case "url" -> { String u = plugin.getConfig().getString("pack.host.hud-url", ""); yield u == null || u.isBlank() ? null : u.trim(); }
            default -> null;
        };
    }

    public void send(Player p) {
        if (!enabled() || mode().equals("folder")) return;
        if (sha1.isEmpty()) refreshHash();
        if (sha1.isEmpty()) return;
        String url = urlFor(p);
        if (url == null) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1);
            boolean required = plugin.getConfig().getBoolean("pack.host.required", false);
            Component prompt = plugin.messages().parse(plugin.getConfig().getString("pack.host.prompt", "<gold>SuperbVehicles</gold> vehicle models"));
            java.util.List<ResourcePackInfo> packs = new java.util.ArrayList<>(java.util.List.of(info));
            String hudUrl = hudAvailable() && plugin.hud().wantsShaderPack(p) ? hudUrlFor(p) : null;
            if (hudUrl != null) packs.add(ResourcePackInfo.resourcePackInfo(HUD_PACK_ID, URI.create(hudUrl), hudSha1));
            p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(packs).replace(false).required(required).prompt(prompt).build());
        } catch (Exception e) { plugin.getLogger().warning("Could not send the pack to " + p.getName() + ": " + e); }
    }

    /** After a build: new hash, folder merge, re-send to everyone online. */
    public void packChanged() {
        refreshHash();
        if (mode().equals("folder")) { mergeIntoFolder(); return; }
        for (Player p : plugin.getServer().getOnlinePlayers()) send(p);
    }

    public String status() {
        if (!enabled()) return "pack host disabled (pack.host.enabled: false)";
        if (!lastProblem.isEmpty()) return "pack host PROBLEM: " + lastProblem;
        return switch (mode()) {
            case "same-port" -> samePort == null ? "pack host not running" : "same-port mode: http://<server address>/superbvehicles/<sha1>.zip (" + samePort.served() + " download(s) so far)";
            case "self" -> server == null ? "pack host not running" : "built-in server on port " + port();
            case "url" -> "url mode: " + plugin.getConfig().getString("pack.host.url", "");
            case "folder" -> "folder mode: merged into " + plugin.getConfig().getString("pack.host.folder", "");
            default -> "unknown mode '" + mode() + "'";
        };
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        int delay = plugin.getConfig().getInt("pack.host.join-delay-ticks", 20);
        plugin.sched().later(delay, () -> { if (e.getPlayer().isOnline()) send(e.getPlayer()); });
    }

    @EventHandler public void onStatus(PlayerResourcePackStatusEvent e) {
        if (e.getID() != null && e.getID().equals(HUD_PACK_ID)) {
            var st = e.getStatus();
            if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) plugin.hud().shaderPackStatus(e.getPlayer(), true, st.name());
            else if (st != PlayerResourcePackStatusEvent.Status.ACCEPTED && st != PlayerResourcePackStatusEvent.Status.DOWNLOADED) plugin.hud().shaderPackStatus(e.getPlayer(), false, st.name());
            return;
        }
        if (e.getID() == null || !e.getID().equals(PACK_ID)) return;
        var st = e.getStatus();
        if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED || st == PlayerResourcePackStatusEvent.Status.ACCEPTED || st == PlayerResourcePackStatusEvent.Status.DOWNLOADED || st == PlayerResourcePackStatusEvent.Status.DECLINED) return;
        String url = urlFor(e.getPlayer());
        String hint = switch (mode()) {
            case "same-port" -> "the client could not fetch " + url + " - behind a proxy? set pack.host.mode: self or url; otherwise set pack.host.address to the host players use.";
            case "self" -> "the client could not fetch " + url + " - port " + port() + " is not reachable from outside (open it, or set pack.host.address), or switch to pack.host.mode: same-port.";
            default -> "the client could not fetch " + url + " - check that the URL serves the CURRENT pack (sha1 " + sha1 + ").";
        };
        lastProblem = st + " for " + e.getPlayer().getName() + ": " + hint;
        if (System.currentTimeMillis() - lastFailLog > 10_000) {
            lastFailLog = System.currentTimeMillis();
            plugin.getLogger().warning("Resource pack " + st + " for " + e.getPlayer().getName() + ": " + hint);
        }
    }
}
