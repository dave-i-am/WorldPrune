package dev.minecraft.prune;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class WorldPrunePlugin extends JavaPlugin {
    private PlanService planService;
    private HeuristicService heuristicService;
    private ApplyService applyService;
    private RestoreService restoreService;
    private PurgeService purgeService;
    private PlanStore planStore;
    private ScheduleService scheduleService;
    private CoreProtectProvider coreProtectProvider;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        java.nio.file.Path reportsRoot = getDataFolder().toPath().resolve("reports");
        try {
            java.nio.file.Files.createDirectories(reportsRoot);
        } catch (Exception e) {
            getLogger().severe("Failed to create reports directory: " + e);
        }

        this.planStore = new PlanStore(reportsRoot);
        this.heuristicService = new HeuristicService(this, planStore);
        this.coreProtectProvider = new CoreProtectProvider(getLogger(), getServer().getWorldContainer());
        heuristicService.setCoreProtectProvider(this.coreProtectProvider);
        this.planService = new PlanService(this, planStore, heuristicService);
        this.applyService = new ApplyService(this, planStore);
        this.restoreService = new RestoreService(this);
        this.purgeService = new PurgeService(this);

        // Build schedule service early so PruneCommand can reference it
        this.scheduleService = new ScheduleService(this, planService, applyService, purgeService);

        PluginCommand prune = getCommand("prune");
        if (prune != null) {
            PruneCommand command = new PruneCommand(this, planService, heuristicService, applyService, restoreService, purgeService, planStore);
            command.setCoreProtectProvider(this.coreProtectProvider);
            command.setScheduleService(this.scheduleService);
            prune.setExecutor(command);
            prune.setTabCompleter(command);
        } else {
            getLogger().severe("Command /prune is missing from plugin.yml");
        }

        // Start automated scheduler if enabled in config
        if (getConfig().getBoolean("schedule.enabled", false)) {
            getLogger().warning("[WorldPrune] Automated scheduled pruning is ENABLED. Region files will be quarantined unattended.");
            scheduleService.start();
        }

        getLogger().info("WorldPrune enabled.");

        // Warn about any stale apply locks left by a previous hard crash
        for (org.bukkit.World w : getServer().getWorlds()) {
            try {
                String stale = applyService.getStaleLockInfo(w);
                if (stale != null) {
                    getLogger().warning("[WorldPrune] Stale apply lock detected for world '" + w.getName()
                            + "' (interrupted apply: " + stale + "). Run /prune apply " + w.getName()
                            + " to review, then use --force-unlock to clear it.");
                }
            } catch (Exception e) {
                getLogger().warning("[WorldPrune] Could not check lock for world '" + w.getName() + "': " + e.getMessage());
            }
        }
    }

    public PlanService getPlanService() {
        return planService;
    }

    public HeuristicService getHeuristicService() {
        return heuristicService;
    }

    public ApplyService getApplyService() {
        return applyService;
    }

    public RestoreService getRestoreService() {
        return restoreService;
    }

    public PurgeService getPurgeService() {
        return purgeService;
    }

    public PlanStore getPlanStore() {
        return planStore;
    }

    public ScheduleService getScheduleService() {
        return scheduleService;
    }

    @Override
    public void onDisable() {
        if (scheduleService != null) scheduleService.stop();
    }
}
