package fi.alavesa.superbvehicles.core;

import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/** Scheduler facade: Paper's global-region and async schedulers (present on Paper and Folia alike). */
public final class Sched {
    private final Plugin plugin;
    public Sched(Plugin plugin) { this.plugin = plugin; }

    public void run(Runnable r) { plugin.getServer().getGlobalRegionScheduler().execute(plugin, r); }
    public void later(long ticks, Runnable r) { plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> r.run(), Math.max(1, ticks)); }
    public io.papermc.paper.threadedregions.scheduler.ScheduledTask repeat(long delay, long period, Runnable r) { return plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> r.run(), Math.max(1, delay), Math.max(1, period)); }
    public void async(Runnable r) { plugin.getServer().getAsyncScheduler().runNow(plugin, t -> r.run()); }
    public void asyncThen(Runnable work, Consumer<Throwable> onMain) {
        async(() -> { Throwable err = null; try { work.run(); } catch (Throwable t) { err = t; } final Throwable e = err; run(() -> onMain.accept(e)); });
    }
}
