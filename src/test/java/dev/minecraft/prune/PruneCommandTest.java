package dev.minecraft.prune;

import org.bukkit.command.PluginCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for PruneCommand dispatching using MockBukkit.
 * Covers: permission gating, subcommand routing, synchronous outputs.
 * Async operations (scan, apply, undo, drop) are covered by e2e/run.sh.
 */
@SuppressWarnings("removal") // assertSaid(String) is scheduled for removal in a future MockBukkit version
class PruneCommandTest {

    private ServerMock server;
    private WorldPrunePlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(WorldPrunePlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void dispatch(org.bukkit.command.CommandSender sender, String... args) {
        PluginCommand cmd = plugin.getCommand("prune");
        assertNotNull(cmd, "/prune command must be registered in plugin.yml");
        cmd.execute(sender, "prune", args);
    }

    private PlayerMock opPlayer() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        return p;
    }

    // ── permission gate ───────────────────────────────────────────────────────

    @Test
    void unauthorizedPlayer_isRejected() {
        PlayerMock player = server.addPlayer(); // non-op, no permissions
        // Call the executor directly (bypassing PluginCommand's framework-level
        // permission check) so we exercise PruneCommand's own hasPermission guard.
        PluginCommand cmd = plugin.getCommand("prune");
        assertNotNull(cmd);
        cmd.getExecutor().onCommand(player, cmd, "prune", new String[0]);
        player.assertSaid("§cYou do not have permission to use /prune.");
    }

    @Test
    void applySubcommand_withoutApplyPerm_isRejected() {
        // op has prune.admin but let's verify a non-op player sees the apply perm error
        // Give prune.admin but NOT prune.admin.apply
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "prune.admin", true);
        player.addAttachment(plugin, "prune.admin.apply", false);
        dispatch(player, "apply");
        player.assertSaid("§cYou do not have permission to apply prune plans.");
    }

    // ── subcommand routing ────────────────────────────────────────────────────

    @Test
    void noArgs_showsUsage() {
        PlayerMock player = opPlayer();
        dispatch(player);
        player.assertSaid("§e/prune scan §7[world]              §f- Analyse world and generate a plan");
    }

    @Test
    void unknownSubcommand_showsError() {
        PlayerMock player = opPlayer();
        dispatch(player, "bogus");
        player.assertSaid("§cUnknown subcommand: bogus");
    }

    // ── status ────────────────────────────────────────────────────────────────

    @Test
    void status_sendsBannerAndSourceFields() {
        PlayerMock player = opPlayer();
        dispatch(player, "status");
        player.assertSaid("§8§m══════════════════════════════════════");
        player.assertSaid("§e§lWorldPrune Status");
        player.assertSaid("§7Source:         §fcombined"); // default from config.yml
        player.assertSaid("§7Keep-rules mode: §fentity-aware");
        player.nextMessage(); // claim margin
        player.nextMessage(); // quarantine only
        player.nextMessage(); // confirm token
        player.assertSaid("§7CoreProtect:    §7inactive"); // CP not installed in test env
    }

    // ── plans ─────────────────────────────────────────────────────────────────

    @Test
    void plans_withNoPlans_showsEmptyMessage() {
        PlayerMock player = opPlayer();
        dispatch(player, "plans");
        player.nextMessage(); // separator banner
        player.nextMessage(); // "Plans" header
        player.assertSaid("§7  No plans found. Run §f/prune scan§7.");
    }

    @Test
    void plan_missingArg_showsUsageError() {
        PlayerMock player = opPlayer();
        dispatch(player, "plan");
        player.assertSaid("§cUsage: /prune plan <planId>");
    }

    @Test
    void plan_unknownId_showsNotFound() {
        PlayerMock player = opPlayer();
        dispatch(player, "plan", "plan-nonexistent-00000000-000000");
        player.assertSaid("§cPlan not found: §fplan-nonexistent-00000000-000000");
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    void confirm_withNothingStaged_showsNothingToConfirm() {
        PlayerMock player = opPlayer();
        dispatch(player, "confirm");
        player.assertSaid("§cNothing to confirm. Run §f/prune apply §cor §f/prune drop §cfirst.");
    }

    // ── scan initiates async work ─────────────────────────────────────────────

    @Test
    void scan_sendsProgressMessageAndSchedulesTask() {
        PlayerMock player = opPlayer();
        dispatch(player, "scan", "world");
        // Command immediately acknowledges before async work starts
        String first = player.nextMessage();
        assertNotNull(first, "scan must send an immediate progress message");
        // Wait briefly for the async task to finish (it may log an error since
        // no real region files exist in the mock world directory, but should not throw)
        server.getScheduler().waitAsyncTasksFinished();
    }
}
