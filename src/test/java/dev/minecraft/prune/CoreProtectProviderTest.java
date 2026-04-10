package dev.minecraft.prune;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CoreProtectProvider}.
 *
 * Uses real temp SQLite databases — no Bukkit server or CoreProtect plugin
 * is required.  The direct-DB approach means there is no {@code api.enabled}
 * requirement to worry about.
 */
class CoreProtectProviderTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir Path tmp;

    // ── isAvailable ──────────────────────────────────────────────────────────

    @Test
    void isUnavailableWhenDbAbsent() {
        var p = new CoreProtectProvider(LOG, tmp.resolve("missing.db").toFile(), false);
        assertFalse(p.isAvailable());
    }

    @Test
    void isAvailableWhenDbExists() throws Exception {
        File db = createSchema(tmp);
        var p = new CoreProtectProvider(LOG, db, false);
        assertTrue(p.isAvailable());
    }

    @Test
    void stubConstructor_unavailable() {
        assertFalse(new CoreProtectProvider(false).isAvailable());
    }

    @Test
    void stubConstructor_available() {
        assertTrue(new CoreProtectProvider(true).isAvailable());
    }

    // ── hasRecentActivity: guard paths ───────────────────────────────────────

    @Test
    void returnsFalseWhenUnavailable() {
        var p = new CoreProtectProvider(LOG, tmp.resolve("no.db").toFile(), false);
        assertFalse(p.hasRecentActivity("world", 0, 0, 30));
    }

    // ── hasRecentActivity: real queries ──────────────────────────────────────

    @Test
    void detectsActivityInRegion() throws Exception {
        File db = createSchema(tmp);
        seedBlock(db, "world", 256, 64, 256, 1);   // centre of r.0.0, 1 day ago
        var p = new CoreProtectProvider(LOG, db, false);
        assertTrue(p.hasRecentActivity("world", 0, 0, 30),
                "Activity at (256,256) → r.0.0 should be detected");
    }

    @Test
    void noActivityReturnsFalse() throws Exception {
        File db = createSchema(tmp);
        seedBlock(db, "world", 256, 64, 256, 1);   // activity in r.0.0 only
        var p = new CoreProtectProvider(LOG, db, false);
        assertFalse(p.hasRecentActivity("world", 1, 0, 30),
                "r.1.0 has no activity");
    }

    @Test
    void wrongWorldReturnsFalse() throws Exception {
        File db = createSchema(tmp);
        seedBlock(db, "world", 256, 64, 256, 1);
        var p = new CoreProtectProvider(LOG, db, false);
        assertFalse(p.hasRecentActivity("other_world", 0, 0, 30),
                "Activity exists only in 'world'");
    }

    @Test
    void activityOutsideLookbackWindowReturnsFalse() throws Exception {
        File db = createSchema(tmp);
        seedBlock(db, "world", 256, 64, 256, 400);  // 400 days ago
        var p = new CoreProtectProvider(LOG, db, false);
        assertFalse(p.hasRecentActivity("world", 0, 0, 30),
                "400-day-old activity should not match a 30-day window");
    }

    @Test
    void corruptDbReturnsTrueNotException() throws Exception {
        // An empty file is not a valid SQLite DB — should fail-safe (treat as activity → keep).
        File corrupt = tmp.resolve("corrupt.db").toFile();
        assertTrue(corrupt.createNewFile());
        var p = new CoreProtectProvider(LOG, corrupt, false);
        assertTrue(p.hasRecentActivity("world", 0, 0, 30),
                "Corrupt DB should return true (fail-safe: conservative keep), not throw");
    }

    @Test
    void stubBehaviourOverride_returnsTrue() {
        var p = new CoreProtectProvider(true) {
            @Override boolean hasRecentActivity(String w, int rx, int rz, int d) { return true; }
        };
        assertTrue(p.hasRecentActivity("world", 0, 0, 30));
    }

    @Test
    void stubBehaviourOverride_returnsFalse() {
        var p = new CoreProtectProvider(true) {
            @Override boolean hasRecentActivity(String w, int rx, int rz, int d) { return false; }
        };
        assertFalse(p.hasRecentActivity("world", 5, 5, 90));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Create a minimal CoreProtect-compatible SQLite schema in {@code dir}. */
    private File createSchema(Path dir) throws Exception {
        File db = dir.resolve("database.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE co_world (
                    id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    world TEXT NOT NULL UNIQUE
                )""");
            s.executeUpdate("""
                CREATE TABLE co_user (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    user TEXT NOT NULL UNIQUE,
                    uuid TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE co_block (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    time        INTEGER NOT NULL,
                    user        INTEGER NOT NULL,
                    wid         INTEGER NOT NULL,
                    x           INTEGER NOT NULL,
                    y           INTEGER NOT NULL,
                    z           INTEGER NOT NULL,
                    type        INTEGER NOT NULL DEFAULT 0,
                    action      INTEGER NOT NULL DEFAULT 1,
                    rolled_back INTEGER NOT NULL DEFAULT 0
                )""");
        }
        return db;
    }

    /** Insert one block event at {@code (x,y,z)} in {@code world}, {@code daysAgo} days ago. */
    private void seedBlock(File db, String world, int x, int y, int z, int daysAgo)
            throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT OR IGNORE INTO co_world (world) VALUES ('" + world + "')");
            s.executeUpdate("INSERT OR IGNORE INTO co_user (user,uuid) VALUES ('tester','test-uuid')");
        }
        long t = System.currentTimeMillis() / 1000L - (long) daysAgo * 86400;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO co_block (time,user,wid,x,y,z,type,action,rolled_back) " +
                     "SELECT ?,u.id,w.id,?,?,?,1,1,0 " +
                     "FROM co_user u, co_world w WHERE u.user='tester' AND w.world=?")) {
            ps.setLong(1, t);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.setString(5, world);
            ps.executeUpdate();
        }
    }
}
