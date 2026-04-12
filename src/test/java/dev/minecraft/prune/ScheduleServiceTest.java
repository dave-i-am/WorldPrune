package dev.minecraft.prune;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleServiceTest {

    @TempDir Path tmp;

    WorldPrunePlugin plugin;
    ScheduleService service;

    @BeforeEach
    void setUp() {
        plugin = mock(WorldPrunePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(tmp.toFile());

        // Default config stubs – individual tests may override
        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(cfg.getBoolean("schedule.enabled", false)).thenReturn(false);
        when(cfg.getDouble("schedule.intervalHours", 168.0)).thenReturn(168.0);
        when(cfg.getStringList("schedule.worlds")).thenReturn(List.of());
        when(plugin.getConfig()).thenReturn(cfg);

        service = new ScheduleService(plugin, null, null, null);
    }

    // ─── getStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_disabled_noWorlds() {
        ScheduleService.ScheduleStatus s = service.getStatus();

        assertFalse(s.enabled());
        assertEquals(168.0, s.intervalHours());
        assertTrue(s.configuredWorlds().isEmpty());
        assertTrue(s.lastRunMs().isEmpty());
    }

    @Test
    void getStatus_enabled_returnsConfigValues() {
        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(cfg.getBoolean("schedule.enabled", false)).thenReturn(true);
        when(cfg.getDouble("schedule.intervalHours", 168.0)).thenReturn(24.0);
        when(cfg.getStringList("schedule.worlds")).thenReturn(List.of("world", "world_nether"));
        when(plugin.getConfig()).thenReturn(cfg);

        ScheduleService.ScheduleStatus s = service.getStatus();

        assertTrue(s.enabled());
        assertEquals(24.0, s.intervalHours());
        assertEquals(List.of("world", "world_nether"), s.configuredWorlds());
    }

    @Test
    void getStatus_noStateFile_allWorlds_returnZero() {
        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(cfg.getBoolean("schedule.enabled", false)).thenReturn(true);
        when(cfg.getDouble("schedule.intervalHours", 168.0)).thenReturn(168.0);
        when(cfg.getStringList("schedule.worlds")).thenReturn(List.of("world"));
        when(plugin.getConfig()).thenReturn(cfg);

        ScheduleService.ScheduleStatus s = service.getStatus();

        assertEquals(0L, s.lastRunMs().get("world"));
    }

    @Test
    void getStatus_readsPersistedTimestamp() throws IOException {
        long timestamp = 1_700_000_000_000L;

        Properties p = new Properties();
        p.setProperty("world", String.valueOf(timestamp));
        Path stateFile = tmp.resolve("schedule-state.properties");
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            p.store(out, null);
        }

        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(cfg.getBoolean("schedule.enabled", false)).thenReturn(true);
        when(cfg.getDouble("schedule.intervalHours", 168.0)).thenReturn(168.0);
        when(cfg.getStringList("schedule.worlds")).thenReturn(List.of("world"));
        when(plugin.getConfig()).thenReturn(cfg);

        ScheduleService.ScheduleStatus s = service.getStatus();

        assertEquals(timestamp, s.lastRunMs().get("world"));
    }

    @Test
    void getStatus_corruptTimestamp_treatedAsZero() throws IOException {
        Properties p = new Properties();
        p.setProperty("world", "not-a-number");
        Path stateFile = tmp.resolve("schedule-state.properties");
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            p.store(out, null);
        }

        FileConfiguration cfg = mock(FileConfiguration.class);
        when(cfg.getString("storage.dataRoot", "")).thenReturn("");
        when(cfg.getBoolean("schedule.enabled", false)).thenReturn(true);
        when(cfg.getDouble("schedule.intervalHours", 168.0)).thenReturn(168.0);
        when(cfg.getStringList("schedule.worlds")).thenReturn(List.of("world"));
        when(plugin.getConfig()).thenReturn(cfg);

        ScheduleService.ScheduleStatus s = service.getStatus();

        assertEquals(0L, s.lastRunMs().get("world"));
    }

    // ─── ScheduleStatus.nextRunMs ──────────────────────────────────────────────

    @Test
    void nextRunMs_neverRun_returnsZero() {
        ScheduleService.ScheduleStatus s = new ScheduleService.ScheduleStatus(
                true, 168.0, List.of("world"), Map.of("world", 0L));

        assertEquals(0L, s.nextRunMs("world"));
    }

    @Test
    void nextRunMs_lastRunSet_addsInterval() {
        long last = 1_700_000_000_000L;
        ScheduleService.ScheduleStatus s = new ScheduleService.ScheduleStatus(
                true, 24.0, List.of("world"), Map.of("world", last));

        long expected = last + (long) (24.0 * 3_600_000);
        assertEquals(expected, s.nextRunMs("world"));
    }

    @Test
    void nextRunMs_unknownWorld_returnsZero() {
        ScheduleService.ScheduleStatus s = new ScheduleService.ScheduleStatus(
                true, 168.0, List.of(), Map.of());

        assertEquals(0L, s.nextRunMs("unknown"));
    }

    // ─── ScheduleStatus.formatTime ────────────────────────────────────────────

    @Test
    void formatTime_zero_returnsNever() {
        ScheduleService.ScheduleStatus s = new ScheduleService.ScheduleStatus(
                false, 168.0, List.of(), Map.of());

        assertEquals("never", s.formatTime(0L));
    }

    @Test
    void formatTime_nonZero_returnsFormattedString() {
        ScheduleService.ScheduleStatus s = new ScheduleService.ScheduleStatus(
                false, 168.0, List.of(), Map.of());

        String result = s.formatTime(1_700_000_000_000L);
        // Should not return "never" and should contain a year-like token
        assertNotEquals("never", result);
        assertTrue(result.contains("20"), "expected year in result: " + result);
    }
}
