package dev.minecraft.prune;

import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Automated scheduled prune: generates a combined plan then immediately applies it
 * (quarantine-only) for each configured world on a fixed interval.
 *
 * <p>State (last-run timestamp per world) is persisted to
 * {@code <dataRoot>/schedule-state.properties} so restarts don't reset the clock.
 *
 * <p>Enabled via {@code schedule.enabled: true} in config.yml. See the WARNING
 * comment in config.yml — this runs fully unattended.
 */
public final class ScheduleService {

    private static final long TICKS_PER_HOUR = 20L * 60 * 60;

    private final WorldPrunePlugin plugin;
    private final PlanService planService;
    private final ApplyService applyService;
    private final PurgeService purgeService;

    private BukkitTask task;

    public ScheduleService(WorldPrunePlugin plugin,
                           PlanService planService,
                           ApplyService applyService,
                           PurgeService purgeService) {
        this.plugin      = plugin;
        this.planService = planService;
        this.applyService = applyService;
        this.purgeService = purgeService;
    }

    /** Start the hourly heartbeat. Call once from {@code onEnable}. */
    public void start() {
        // Check every hour whether any configured world is due for a run.
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::heartbeat, TICKS_PER_HOUR, TICKS_PER_HOUR);
        plugin.getLogger().info("[Schedule] Automated prune scheduler started (checking every hour).");
    }

    /** Cancel the repeating task. Call from {@code onDisable}. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ── heartbeat ─────────────────────────────────────────────────────────────

    private void heartbeat() {
        long intervalMs = (long) (plugin.getConfig().getDouble("schedule.intervalHours", 168.0) * 3_600_000);
        List<String> worlds = plugin.getConfig().getStringList("schedule.worlds");
        if (worlds.isEmpty()) return;

        Properties state = loadState();
        long now = System.currentTimeMillis();

        for (String worldName : worlds) {
            long lastRun = Long.parseLong(state.getProperty(worldName, "0"));
            if (now - lastRun < intervalMs) continue;

            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("[Schedule] World '" + worldName + "' is not loaded — skipping.");
                continue;
            }

            broadcast("§e[WorldPrune] Scheduled scan starting for world '" + worldName + "'…");
            runForWorld(world);
            state.setProperty(worldName, String.valueOf(System.currentTimeMillis()));
        }

        saveState(state);
    }

    private void runForWorld(World world) {
        Logger log = plugin.getLogger();
        String worldName = world.getName();

        try {
            // 1. Generate combined plan
            int marginChunks = plugin.getConfig().getInt("claims.marginChunks", 5);
            HeuristicMode mode = HeuristicMode.fromString(
                    plugin.getConfig().getString("keepRules.mode", "entity-aware"));

            broadcast("§7[WorldPrune] Scanning '" + worldName + "'…");
            PlanResult plan = planService.generateCombinedPlan(world, marginChunks, mode);

            broadcast("§7[WorldPrune] Plan ready: " + plan.planId()
                    + " — applying to quarantine…");

            // 2. Apply immediately using the plan's own token (no human needed)
            Consumer<String> progress = msg -> {
                log.info("[Schedule][" + worldName + "] " + stripColour(msg));
                broadcast("§7[WorldPrune] " + msg);
            };

            String applyId = applyService.applyPlan(world, plan.planId(),
                    plan.confirmToken(), /*quarantineOnly=*/true, progress);

            broadcast("§a[WorldPrune] Scheduled apply complete for '" + worldName
                    + "'. Apply ID: " + applyId
                    + "  |  Run §f/prune undo " + worldName + " §ato reverse.");

            // Auto-purge old quarantine entries if configured
            if (plugin.getConfig().getBoolean("schedule.autoPurge.enabled", false)) {
                runAutoPurge(world);
            }

        } catch (IOException e) {
            log.severe("[Schedule] Failed for world '" + worldName + "': " + e.getMessage());
            broadcast("§c[WorldPrune] Scheduled prune FAILED for '" + worldName
                    + "': " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void runAutoPurge(World world) {
        long retainMs = (long) (plugin.getConfig().getDouble("schedule.autoPurge.retainDays", 30.0) * 86_400_000L);
        long cutoff   = System.currentTimeMillis() - retainMs;
        Logger log    = plugin.getLogger();
        String worldName = world.getName();

        try {
            List<PurgeService.ApplyDirInfo> entries = purgeService.listQuarantine(world);
            int purged = 0;
            for (PurgeService.ApplyDirInfo entry : entries) {
                // Parse timestamp from applyId: apply-YYYYMMDD-HHmmss
                long ts = parseApplyTimestamp(entry.applyId());
                if (ts == 0 || ts > cutoff) continue;

                String token = PurgeService.tokenForApply(entry.applyId());
                try {
                    purgeService.purge(world, entry.applyId(), token, msg ->
                            log.info("[Schedule][AutoPurge][" + worldName + "] " + stripColour(msg)));
                    purged++;
                } catch (IOException ex) {
                    log.warning("[Schedule][AutoPurge] Could not purge " + entry.applyId() + ": " + ex.getMessage());
                }
            }
            if (purged > 0) {
                broadcast("§c[WorldPrune] Auto-purged " + purged
                        + " old quarantine entr" + (purged == 1 ? "y" : "ies")
                        + " for '" + worldName + "' (older than "
                        + plugin.getConfig().getInt("schedule.autoPurge.retainDays", 30) + " days).");
            }
        } catch (IOException e) {
            log.warning("[Schedule][AutoPurge] Failed to list quarantine for '" + worldName + "': " + e.getMessage());
        }
    }

    /**
     * Parse the epoch-ms encoded in an applyId like "apply-20260405-182301".
     * Returns 0 if the format is unparseable.
     */
    private static long parseApplyTimestamp(String applyId) {
        try {
            // apply-YYYYMMDD-HHmmss
            String[] parts = applyId.split("-", 3);
            if (parts.length < 3) return 0;
            String datePart = parts[1]; // YYYYMMDD
            String timePart = parts[2]; // HHmmss
            if (datePart.length() != 8 || timePart.length() != 6) return 0;
            java.time.LocalDateTime ldt = java.time.LocalDateTime.of(
                    Integer.parseInt(datePart.substring(0, 4)),
                    Integer.parseInt(datePart.substring(4, 6)),
                    Integer.parseInt(datePart.substring(6, 8)),
                    Integer.parseInt(timePart.substring(0, 2)),
                    Integer.parseInt(timePart.substring(2, 4)),
                    Integer.parseInt(timePart.substring(4, 6)));
            return ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private void broadcast(String message) {
        plugin.getServer().broadcast(message, "prune.admin");
    }

    /** Strip §x colour codes for log output. */
    private static String stripColour(String s) {
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private Path stateFile() {
        String root = plugin.getConfig().getString("storage.dataRoot", "");
        Path base = (root == null || root.isBlank())
                ? plugin.getDataFolder().toPath()
                : Path.of(root);
        return base.resolve("schedule-state.properties");
    }

    private Properties loadState() {
        Properties p = new Properties();
        Path file = stateFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                plugin.getLogger().warning("[Schedule] Could not read state file: " + e.getMessage());
            }
        }
        return p;
    }

    private void saveState(Properties state) {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                state.store(out, "WorldPrune schedule state — do not edit manually");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Schedule] Could not save state file: " + e.getMessage());
        }
    }
}
