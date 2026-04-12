package dev.minecraft.prune;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PruneCommand implements TabExecutor {

    private enum ConfirmType { APPLY, DROP }

    private record PendingConfirm(
            ConfirmType type,
            World world,
            PlanStore.PlanMetadata meta,
            String applyId,
            boolean forceUnlock,
            long expiresAt) {}

    private static final long CONFIRM_TIMEOUT_MS = 30_000;

    private final WorldPrunePlugin plugin;
    private final PlanService planService;
    private final HeuristicService heuristicService;
    private final ApplyService applyService;
    private final RestoreService restoreService;
    private final PurgeService purgeService;
    private final PlanStore planStore;
    private final Map<String, PendingConfirm> pendingConfirms = new ConcurrentHashMap<>();
    private CoreProtectProvider coreProtectProvider; // optional – set after construction
    private ScheduleService scheduleService;         // optional – set after construction

    public PruneCommand(WorldPrunePlugin plugin, PlanService planService, HeuristicService heuristicService, ApplyService applyService, RestoreService restoreService, PurgeService purgeService, PlanStore planStore) {
        this.plugin = plugin;
        this.planService = planService;
        this.heuristicService = heuristicService;
        this.applyService = applyService;
        this.restoreService = restoreService;
        this.purgeService = purgeService;
        this.planStore = planStore;
    }

    void setCoreProtectProvider(CoreProtectProvider provider) {
        this.coreProtectProvider = provider;
    }

    void setScheduleService(ScheduleService svc) {
        this.scheduleService = svc;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("prune.admin")) {
            sender.sendMessage("§cYou do not have permission to use /prune.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "scan"       -> handleScan(sender, args);
            case "plans"      -> handlePlanList(sender, args.length > 1 ? args[1] : null);
            case "plan"       -> handlePlanShow(sender, args);
            case "apply" -> {
                if (!sender.hasPermission("prune.admin.apply")) {
                    sender.sendMessage("§cYou do not have permission to apply prune plans.");
                } else {
                    handleApply(sender, args);
                }
            }
            case "confirm" -> {
                if (!sender.hasPermission("prune.admin.apply") && !sender.hasPermission("prune.admin.purge")) {
                    sender.sendMessage("§cYou do not have permission.");
                } else {
                    handleConfirm(sender);
                }
            }
            case "undo" -> {
                if (!sender.hasPermission("prune.admin.apply")) {
                    sender.sendMessage("§cYou do not have permission to restore quarantine.");
                } else {
                    handleUndo(sender, args);
                }
            }
            case "quarantine" -> handleQuarantine(sender, args);
            case "drop" -> {
                if (!sender.hasPermission("prune.admin.purge")) {
                    sender.sendMessage("§cYou do not have permission to delete quarantine.");
                } else {
                    handleDrop(sender, args);
                }
            }
            case "map"    -> handleMapGive(sender, args);
            case "status"  -> handleStatus(sender);
            case "schedule" -> handleScheduleStatus(sender);
            default        -> { sender.sendMessage("§cUnknown subcommand: " + sub); sendUsage(sender); }
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§e/prune scan §7[world]              §f- Analyse world and generate a plan");
        sender.sendMessage("§e/prune apply §7[world]             §f- Preview apply and stage for confirmation");
        sender.sendMessage("§e/prune confirm                    §f- Execute a staged operation");
        sender.sendMessage("§e/prune undo §7[world] [apply-id]   §f- Restore from quarantine");
        sender.sendMessage("§e/prune quarantine §7[world]        §f- List quarantine directories");
        sender.sendMessage("§e/prune drop §7<world> <apply-id>   §f- Permanently delete a quarantine");
        sender.sendMessage("§e/prune plans §7[world]             §f- List stored plans");
        sender.sendMessage("§e/prune plan §7<planId>             §f- Show details for a plan");
        sender.sendMessage("§e/prune map §7[world] [planId]       §f- Give yourself a map item of a plan");
        sender.sendMessage("§e/prune status                     §f- Show config and recent plans");
        sender.sendMessage("§e/prune schedule                   §f- Show schedule status and last/next run times");
    }

    // ─────────────────────────── MAP ITEM ────────────────────────────────────

    private void handleMapGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by a player in-game.");
            return;
        }

        String worldArg = args.length > 1 ? args[1] : null;
        String planIdArg = args.length > 2 ? args[2] : null;

        World world = worldArg != null ? Bukkit.getWorld(worldArg) : player.getWorld();
        if (world == null) {
            sender.sendMessage("§cWorld not found: §f" + worldArg);
            return;
        }

        try {
            PlanStore.PlanMetadata meta;
            if (planIdArg != null) {
                meta = planStore.loadPlanMetadata(planIdArg);
                if (meta == null) {
                    sender.sendMessage("§cPlan not found: §f" + planIdArg);
                    sender.sendMessage("§7Use §f/prune plans§7 to see available plans.");
                    return;
                }
            } else {
                meta = planStore.getLatestPlan(world.getName());
                if (meta == null) {
                    sender.sendMessage("§cNo plans found for §f" + world.getName() + "§c. Run §f/prune scan§c first.");
                    return;
                }
            }

            Path planDir = planStore.getPlanReportDir(meta.planId).toPath();
            ItemStack mapItem = MapVisualizer.createMap(player, meta, planDir);
            player.getInventory().addItem(mapItem);
            sender.sendMessage("§aMap given for plan §f" + meta.planId);
            sender.sendMessage("§7  §a■§7 keep   §c■§7 prune   §9■§7 keep-zone");
        } catch (IOException e) {
            sender.sendMessage("§c✗ Failed to create map: " + e.getMessage());
            plugin.getLogger().severe("Map give failed: " + e);
        }
    }

    // ─────────────────────────── SCAN ───────────────────────────────────────

    private void handleScan(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args.length > 1 ? args[1] : null);
        if (world == null) {
            sender.sendMessage("§cCould not resolve world. Specify a name or run in-game.");
            return;
        }

        String source       = plugin.getConfig().getString("source", "combined").toLowerCase(Locale.ROOT);
        int    marginChunks = plugin.getConfig().getInt("claims.marginChunks", 5);
        String modeStr      = plugin.getConfig().getString("keepRules.mode", "entity-aware");
        HeuristicMode mode  = HeuristicMode.fromString(modeStr);

        sender.sendMessage("§7Scanning §e" + world.getName() + "§7 [source=§f" + source + "§7]...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                switch (source) {
                    case "claims"    -> displayPlanResult(sender, planService.generatePlan(world, marginChunks), world);
                    case "keepRules" -> displayHeuristicResult(sender, heuristicService.run(world, mode), world);
                    case "combined"  -> displayPlanResult(sender, planService.generateCombinedPlan(world, marginChunks, mode), world);
                    default          -> sender.sendMessage("§cUnknown source in config: " + source + ". Use: claims, keepRules, combined");
                }
            } catch (Exception e) {
                sender.sendMessage("§cScan failed: " + e.getMessage());
                plugin.getLogger().severe("Scan failed: " + e);
            }
        });
    }

    // ─────────────────────── PLAN LIST / SHOW ────────────────────────────────

    private void handlePlanList(CommandSender sender, String worldFilter) {
        try {
            List<PlanStore.PlanMetadata> plans = planStore.listPlans(worldFilter);
            sender.sendMessage("§8§m══════════════════════════════════════");
            sender.sendMessage("§e§lPlans§r"
                    + (worldFilter != null ? "§7 (world: §f" + worldFilter + "§7)" : ""));
            if (plans.isEmpty()) {
                sender.sendMessage("§7  No plans found. Run §f/prune scan§7.");
            } else {
                for (PlanStore.PlanMetadata m : plans) {
                    String date = java.time.Instant.ofEpochMilli(m.timestampMs)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    sender.sendMessage("§f" + m.planId
                            + "§7  " + m.worldName
                            + "  §a↑" + m.keepCount + "§r §c↓" + m.pruneCount
                            + "§7  ~§f" + String.format(Locale.ROOT, "%.2f", m.reclaimableGiB) + " GiB"
                            + "§7  " + date);
                }
                sender.sendMessage("§7  §f" + plans.size() + "§7 plan(s). Use §f/prune plan <planId>§7 for details.");
            }
            sender.sendMessage("§8§m══════════════════════════════════════");
        } catch (IOException e) {
            sender.sendMessage("§cFailed to read plan index: " + e.getMessage());
        }
    }

    private void handlePlanShow(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /prune plan <planId>");
            return;
        }
        PlanStore.PlanMetadata meta = loadMeta(args[1]);
        if (meta == null) {
            sender.sendMessage("§cPlan not found: §f" + args[1]);
            sender.sendMessage("§7Use §f/prune plans§7 to see available plans.");
            return;
        }
        String date = java.time.Instant.ofEpochMilli(meta.timestampMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lPlan: §f" + meta.planId);
        sender.sendMessage("§7World:   §f" + meta.worldName);
        sender.sendMessage("§7Source:  §f" + meta.source + " / " + meta.mode);
        sender.sendMessage("§7Margin:  §f" + meta.marginChunks + " §7chunks");
        sender.sendMessage("§7Created: §f" + date);
        sender.sendMessage(" ");
        sender.sendMessage("§a  Keep:  §f" + meta.keepCount + " §7regions");
        sender.sendMessage("§c  Prune: §f" + meta.pruneCount + " §7regions  (§f~"
                + String.format(Locale.ROOT, "%.2f", meta.reclaimableGiB) + " GiB§7 reclaimable)");
        sender.sendMessage(" ");
        displayTopCandidates(sender, planStore.getPlanReportDir(meta.planId).toPath());
        sender.sendMessage(" ");
        sender.sendMessage("§aRun §f/prune apply " + meta.worldName + " §ato apply this plan.");
        sender.sendMessage("§8§m══════════════════════════════════════");
    }
    private void displayPlanResult(CommandSender sender, PlanResult r, World world) {
        PlanStore.PlanMetadata meta = loadMeta(r.planId());
        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lScan complete: §f" + world.getName());
        sender.sendMessage("§7Source:  §f" + r.claimSource());
        if (meta != null) {
            sender.sendMessage("§7Margin:  §f" + meta.marginChunks + " §7chunks");
            sender.sendMessage(" ");
            sender.sendMessage("§a  Keep:  §f" + meta.keepCount + " §7regions");
            sender.sendMessage("§c  Prune: §f" + meta.pruneCount + " §7regions  (§f~"
                    + String.format(Locale.ROOT, "%.2f", meta.reclaimableGiB) + " GiB§7 reclaimable)");
        }
        sender.sendMessage(" ");
        displayTopCandidates(sender, r.reportDir().toPath());
        sender.sendMessage(" ");
        sender.sendMessage("§7Report: §o" + r.reportDir().getPath());
        sender.sendMessage(" ");
        sender.sendMessage("§aRun §f/prune apply " + world.getName() + " §ato review and apply.");
        sender.sendMessage("§8§m══════════════════════════════════════");
    }

    private void displayHeuristicResult(CommandSender sender, HeuristicResult r, World world) {
        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lScan complete: §f" + world.getName());
        sender.sendMessage("§7Source:  §fkeepRules (§f" + r.mode() + "§7)");
        sender.sendMessage(" ");
        sender.sendMessage("§a  Keep:  §f" + r.keepCount() + " §7regions");
        sender.sendMessage("§c  Prune: §f" + r.pruneCount() + " §7regions  (§f~"
                + String.format(Locale.ROOT, "%.2f", r.reclaimableGiBEstimate()) + " GiB§7 reclaimable)");
        sender.sendMessage(" ");
        displayTopCandidates(sender, r.reportDir().toPath());
        sender.sendMessage(" ");
        sender.sendMessage("§7Report: §o" + r.reportDir().getPath());
        sender.sendMessage(" ");
        sender.sendMessage("§aRun §f/prune apply " + world.getName() + " §ato review and apply.");
        sender.sendMessage("§8§m══════════════════════════════════════");
    }

    private void displayTopCandidates(CommandSender sender, Path reportDir) {
        Path candidateFile = findCandidateFile(reportDir);
        if (candidateFile == null) return;
        try {
            List<String> lines = Files.readAllLines(candidateFile, StandardCharsets.UTF_8)
                    .stream().filter(l -> !l.isBlank()).toList();
            int shown = Math.min(5, lines.size());
            if (shown == 0) return;
            sender.sendMessage("§7Sample prunable regions:");
            for (int i = 0; i < shown; i++) sender.sendMessage("  §f" + lines.get(i));
            if (lines.size() > shown) {
                sender.sendMessage("  §7... and §f" + (lines.size() - shown) + " §7more (see report)");
            }
        } catch (IOException ignored) {}
    }

    private Path findCandidateFile(Path dir) {
        try {
            for (Path child : Files.list(dir).toList()) {
                Path base = Files.isDirectory(child) ? child : dir;
                for (String name : List.of(
                        "prune-candidate-regions.txt",
                        "prune-candidate-regions-heuristic-entity-aware.txt",
                        "prune-candidate-regions-heuristic-size.txt")) {
                    Path f = base.resolve(name);
                    if (Files.exists(f)) return f;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ─────────────────────────── APPLY ──────────────────────────

    // ─────────────────────────── APPLY ──────────────────────────────────────

    private void handleApply(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args.length > 1 && !args[1].startsWith("--") ? args[1] : null);
        if (world == null) {
            sender.sendMessage("§cCould not resolve world. Specify a name or run in-game.");
            return;
        }
        boolean forceUnlock = java.util.Arrays.stream(args).anyMatch("--force-unlock"::equalsIgnoreCase);

        try {
            PlanStore.PlanMetadata meta = planStore.getLatestPlan(world.getName());
            if (meta == null) {
                sender.sendMessage("§cNo plan found for §e" + world.getName()
                        + "§c. Run §f/prune scan " + world.getName() + "§c first.");
                return;
            }

            String staleLock = applyService.getStaleLockInfo(world);
            if (staleLock != null && !forceUnlock) {
                sender.sendMessage("§e⚠ Stale apply lock detected (interrupted apply: §f" + staleLock + "§e).");
                sender.sendMessage("§e  The previous apply may be partially complete.");
                sender.sendMessage("§e  Run §f/prune quarantine " + world.getName() + " §eto review orphaned files.");
                sender.sendMessage("§e  Add §f--force-unlock §eto clear the lock: §f/prune apply " + world.getName() + " --force-unlock");
                return;
            }

            showApplyPreview(sender, meta, world, staleLock != null);
            pendingConfirms.put(sender.getName(), new PendingConfirm(
                    ConfirmType.APPLY, world, meta, null, forceUnlock,
                    System.currentTimeMillis() + CONFIRM_TIMEOUT_MS));
            sender.sendMessage("§aType §f/prune confirm §awithin 30 seconds to proceed.");
        } catch (Exception e) {
            sender.sendMessage("§c✗ Apply error: " + e.getMessage());
            plugin.getLogger().severe("Apply error: " + e);
        }
    }

    private void showApplyPreview(CommandSender sender, PlanStore.PlanMetadata meta, World world, boolean hasStale) {
        boolean quarantineOnly = plugin.getConfig().getBoolean("safety.quarantineOnly", true);
        String action = quarantineOnly ? "moved to quarantine §7(reversible)" : "§cDELETED §7(irreversible!)";

        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lApply Preview: §f" + world.getName());
        if (hasStale) sender.sendMessage("§e⚠ Stale lock will be cleared (--force-unlock).");
        sender.sendMessage("§7Plan:    §f" + meta.planId);
        sender.sendMessage("§7Source:  §f" + meta.source + " / " + meta.mode);
        sender.sendMessage(" ");
        sender.sendMessage("§c  " + meta.pruneCount + " §7region files will be " + action);
        sender.sendMessage("§7  ~§f" + String.format(Locale.ROOT, "%.2f", meta.reclaimableGiB) + " GiB §7freed");
        sender.sendMessage("§a  " + meta.keepCount + " §7regions kept untouched");
        sender.sendMessage(" ");
        sender.sendMessage("§7Quarantine: §o<world>/quarantine/<apply-id>/");
        sender.sendMessage(" ");
        displayTopCandidates(sender, planStore.getPlanReportDir(meta.planId).toPath());
        sender.sendMessage("§8§m══════════════════════════════════════");
    }

    private void executeApply(CommandSender sender, PlanStore.PlanMetadata meta, World world, boolean forceUnlock) {
        boolean quarantineOnly = plugin.getConfig().getBoolean("safety.quarantineOnly", true);
        Consumer<String> progress = msg -> sender.sendMessage("§7  " + msg);
        sender.sendMessage("§7Applying plan §f" + meta.planId + "§7...");

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (forceUnlock) {
                    applyService.clearStaleLock(world);
                    progress.accept("§7Stale lock cleared.");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not clear stale lock: " + e.getMessage());
            }
            world.setAutoSave(false);
            world.save();
            progress.accept("§7World flushed to disk, auto-save paused.");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String applyId = applyService.applyPlan(world, meta.planId, meta.confirmToken, quarantineOnly, progress);
                    sender.sendMessage("§a✓ Apply complete (id: §f" + applyId + "§a). To undo: §f/prune undo " + world.getName());
                } catch (Exception e) {
                    sender.sendMessage("§c✗ Apply failed: " + e.getMessage());
                    plugin.getLogger().severe("Apply failed: " + e);
                } finally {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        world.setAutoSave(true);
                        progress.accept("§7Auto-save re-enabled.");
                    });
                }
            });
        });
    }

    // ─────────────────────────── CONFIRM ─────────────────────────────────────

    private void handleConfirm(CommandSender sender) {
        PendingConfirm pending = pendingConfirms.remove(sender.getName());
        if (pending == null) {
            sender.sendMessage("§cNothing to confirm. Run §f/prune apply §cor §f/prune drop §cfirst.");
            return;
        }
        if (System.currentTimeMillis() > pending.expiresAt()) {
            sender.sendMessage("§cConfirmation expired. Re-run the command.");
            return;
        }
        switch (pending.type()) {
            case APPLY -> executeApply(sender, pending.meta(), pending.world(), pending.forceUnlock());
            case DROP  -> executeDrop(sender, pending.world(), pending.applyId());
        }
    }


    // ─────────────────────────── UNDO ────────────────────────────────────────

    private void handleUndo(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args.length > 1 && !args[1].startsWith("--") ? args[1] : null);
        if (world == null) {
            sender.sendMessage("§cCould not resolve world. Specify a name or run in-game.");
            return;
        }
        String applyId = args.length > 2 ? args[2] : null;
        Consumer<String> progress = msg -> sender.sendMessage("§7  " + msg);
        sender.sendMessage("§7Restoring " + (applyId != null ? "apply §f" + applyId : "latest apply")
                + "§7 for §e" + world.getName() + "§7...");

        Bukkit.getScheduler().runTask(plugin, () -> {
            world.setAutoSave(false);
            world.save();
            progress.accept("§7World flushed to disk, auto-save paused.");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    restoreService.restore(world, applyId, progress);
                    sender.sendMessage("§a✓ Restore complete for §e" + world.getName() + "§a.");
                } catch (Exception e) {
                    sender.sendMessage("§c✗ Restore failed: " + e.getMessage());
                    plugin.getLogger().severe("Restore failed: " + e);
                } finally {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        world.setAutoSave(true);
                        progress.accept("§7Auto-save re-enabled.");
                    });
                }
            });
        });
    }

    // ─────────────────────────── QUARANTINE ──────────────────────────────────

    private void handleQuarantine(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args.length > 1 ? args[1] : null);
        if (world == null) {
            sender.sendMessage("§cCould not resolve world. Specify a name or run in-game.");
            return;
        }
        try {
            List<PurgeService.ApplyDirInfo> dirs = purgeService.listQuarantine(world);
            sender.sendMessage("§8§m══════════════════════════════════════");
            sender.sendMessage("§e§lQuarantine: §f" + world.getName());
            if (dirs.isEmpty()) {
                sender.sendMessage("§7  No quarantine directories found.");
            } else {
                for (PurgeService.ApplyDirInfo info : dirs) {
                    String statusLabel = !info.hasManifest() ? "INCOMPLETE" : info.isRestored() ? "RESTORED" : "ACTIVE";
                    String lineColor   = !info.hasManifest() ? "§e" : info.isRestored() ? "§a" : "§c";
                    String files = info.fileCount() > 0 ? info.fileCount() + " files" : "(manifest only)";
                    sender.sendMessage(lineColor + "  " + info.applyId() + " [" + statusLabel + "]  " + files
                            + "  §7(§f" + PurgeService.formatSize(info.sizeBytes()) + "§7)");
                }
                sender.sendMessage(" ");
                sender.sendMessage("§7To permanently delete: §f/prune drop " + world.getName() + " <apply-id>");
            }
            sender.sendMessage("§8§m══════════════════════════════════════");
        } catch (Exception e) {
            sender.sendMessage("§c✗ Error: " + e.getMessage());
            plugin.getLogger().severe("Quarantine list error: " + e);
        }
    }

    // ─────────────────────────── DROP ────────────────────────────────────────

    private void handleDrop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /prune drop <world> <apply-id>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("§cWorld not found: §f" + args[1]);
            return;
        }
        String applyId = args[2];

        try {
            List<PurgeService.ApplyDirInfo> dirs = purgeService.listQuarantine(world);
            PurgeService.ApplyDirInfo info = dirs.stream()
                    .filter(d -> d.applyId().equals(applyId))
                    .findFirst().orElse(null);
            if (info == null) {
                sender.sendMessage("§cQuarantine directory not found: §f" + applyId);
                sender.sendMessage("§7Run §f/prune quarantine " + world.getName() + " §7to see available entries.");
                return;
            }

            String statusLabel = !info.hasManifest() ? "INCOMPLETE" : info.isRestored() ? "RESTORED" : "ACTIVE";
            String statusColor = !info.hasManifest() ? "§e" : info.isRestored() ? "§a" : "§c";
            sender.sendMessage("§8§m══════════════════════════════════════");
            sender.sendMessage("§c§lDrop Preview: §f" + applyId);
            sender.sendMessage("§7World:  §f" + world.getName());
            sender.sendMessage("§7Status: " + statusColor + statusLabel);
            sender.sendMessage("§7Files:  §f" + info.fileCount());
            sender.sendMessage(" ");
            sender.sendMessage("§c§lWARNING: This is permanent and cannot be undone.");
            sender.sendMessage("§8§m══════════════════════════════════════");

            pendingConfirms.put(sender.getName(), new PendingConfirm(
                    ConfirmType.DROP, world, null, applyId, false,
                    System.currentTimeMillis() + CONFIRM_TIMEOUT_MS));
            sender.sendMessage("§aType §f/prune confirm §awithin 30 seconds to proceed.");
        } catch (Exception e) {
            sender.sendMessage("§c✗ Error: " + e.getMessage());
            plugin.getLogger().severe("Drop preview error: " + e);
        }
    }

    private void executeDrop(CommandSender sender, World world, String applyId) {
        sender.sendMessage("§7Deleting §f" + applyId + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String token = PurgeService.tokenForApply(applyId);
                purgeService.purge(world, applyId, token, sender::sendMessage);
            } catch (Exception e) {
                sender.sendMessage("§c✗ Drop failed: " + e.getMessage());
                plugin.getLogger().severe("Drop failed: " + e);
            }
        });
    }


    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lWorldPrune Status");
        sender.sendMessage("§7Source:         §f" + plugin.getConfig().getString("source", "combined"));
        sender.sendMessage("§7Keep-rules mode: §f" + plugin.getConfig().getString("keepRules.mode", "entity-aware"));
        sender.sendMessage("§7Claim margin:   §f" + plugin.getConfig().getInt("claims.marginChunks", 5) + " chunks");
        sender.sendMessage("§7Quarantine only:§f" + plugin.getConfig().getBoolean("safety.quarantineOnly", true));
        sender.sendMessage("§7Confirm token:  §f" + plugin.getConfig().getBoolean("safety.requireConfirmToken", true));
        if (coreProtectProvider != null && coreProtectProvider.isAvailable()) {
            int days = plugin.getConfig().getInt("coreprotect.activityLookbackDays", 180);
            sender.sendMessage("§7CoreProtect:    §aactive §7(§f" + days + "§7-day lookback)");
        } else {
            sender.sendMessage("§7CoreProtect:    §7inactive");
        }
        sender.sendMessage(" ");
        try {
            List<PlanStore.PlanMetadata> recent = planStore.listPlans(null);
            if (recent.isEmpty()) {
                sender.sendMessage("§7No plans yet. Run §f/prune scan§7.");
            } else {
                sender.sendMessage("§7Recent plans:");
                recent.stream().limit(5).forEach(m ->
                    sender.sendMessage("  §f" + m.planId + " §7(" + m.worldName + ") keep=§a"
                            + m.keepCount + " §7prune=§c" + m.pruneCount
                            + " §7(§f~" + String.format(Locale.ROOT, "%.2f", m.reclaimableGiB) + " GiB§7)"));
            }
        } catch (IOException e) {
            sender.sendMessage("§cCould not read plan index: " + e.getMessage());
        }
        sender.sendMessage("§8§m══════════════════════════════════════");
    }

    private void handleScheduleStatus(CommandSender sender) {
        ScheduleService.ScheduleStatus s = scheduleService.getStatus();
        sender.sendMessage("§8§m══════════════════════════════════════");
        sender.sendMessage("§e§lSchedule Status");
        sender.sendMessage("§7Enabled:  " + (s.enabled() ? "§atrue" : "§cfalse"));
        sender.sendMessage("§7Interval: §f" + s.intervalHours() + " hours");
        if (s.configuredWorlds().isEmpty()) {
            sender.sendMessage("§7Worlds:   §7(none configured)");
        } else {
            sender.sendMessage("§7Worlds:");
            for (String world : s.configuredWorlds()) {
                long last = s.lastRunMs().getOrDefault(world, 0L);
                long next = s.nextRunMs(world);
                String lastStr = s.formatTime(last);
                String nextStr = next == 0L ? "on next heartbeat" : s.formatTime(next);
                sender.sendMessage("§7  " + world + "  §7last=§f" + lastStr + "  §7next=§f" + nextStr);
            }
        }
        sender.sendMessage("§8§m══════════════════════════════════════");
    }

    // ─────────────────────────── HELPERS ────────────────────────

    private World resolveWorld(CommandSender sender, String worldArg) {
        if (worldArg != null) return Bukkit.getWorld(worldArg);
        if (sender instanceof Player player) return player.getWorld();
        return null;
    }

    private PlanStore.PlanMetadata loadMeta(String planId) {
        try { return planStore.loadPlanMetadata(planId); } catch (IOException e) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("scan", "plans", "plan", "apply", "confirm", "undo",
                    "quarantine", "drop", "map", "status", "schedule");
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> worldNames = Bukkit.getWorlds().stream().map(World::getName).toList();

        return switch (sub) {
            // Single-arg subcommands: arg 2 = optional world filter
            case "scan", "plans", "quarantine" -> args.length == 2 ? worldNames : List.of();

            // apply: arg 2 = world, arg 3 = flag
            case "apply" -> {
                if (args.length == 2) yield worldNames;
                if (args.length == 3) yield List.of("--force-unlock");
                yield List.of();
            }

            // plan: arg 2 = planId (all worlds)
            case "plan" -> args.length == 2 ? listPlanIds(null) : List.of();

            // undo: arg 2 = world, arg 3 = apply-id of an ACTIVE (not yet restored) apply
            case "undo" -> {
                if (args.length == 2) yield worldNames;
                if (args.length == 3) yield listApplyIds(args[1], false);
                yield List.of();
            }

            // drop: arg 2 = world, arg 3 = apply-id (any state can be dropped)
            case "drop" -> {
                if (args.length == 2) yield worldNames;
                if (args.length == 3) yield listApplyIds(args[1], true);
                yield List.of();
            }

            // map: arg 2 = world, arg 3 = planId scoped to that world
            case "map" -> {
                if (args.length == 2) yield worldNames;
                if (args.length == 3) yield listPlanIds(args[1]);
                yield List.of();
            }

            // No further arguments
            case "confirm", "status", "schedule" -> List.of();

            default -> List.of();
        };
    }

    /** Returns plan IDs, optionally filtered to a specific world. */
    private List<String> listPlanIds(String worldFilter) {
        try {
            return planStore.listPlans(worldFilter).stream()
                    .map(m -> m.planId)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Returns apply IDs from the quarantine of the named world.
     * When {@code allStates} is false, only ACTIVE (restoreable) entries are returned.
     * When {@code allStates} is true, all entries (ACTIVE, RESTORED, INCOMPLETE) are returned.
     */
    private List<String> listApplyIds(String worldName, boolean allStates) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return List.of();
        try {
            return purgeService.listQuarantine(w).stream()
                    .filter(d -> allStates || (d.hasManifest() && !d.isRestored()))
                    .map(PurgeService.ApplyDirInfo::applyId)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
