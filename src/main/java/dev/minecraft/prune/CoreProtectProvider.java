package dev.minecraft.prune;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Soft-dependency adapter for CoreProtect that works by querying its SQLite
 * database file ({@code plugins/CoreProtect/database.db}) directly.
 *
 * <p>Compared to using CoreProtect's Java API this approach has two key
 * advantages:
 * <ul>
 *   <li>No {@code api.enabled: true} setting is needed in CoreProtect's
 *       config — the DB is just a file on disk.</li>
 *   <li>Works even when CoreProtect is currently disabled/unloaded, as long
 *       as the database file exists from a previous run.</li>
 * </ul>
 *
 * <p>If the database file does not exist, {@link #isAvailable()} returns
 * {@code false} and the integration is silently skipped.
 */
class CoreProtectProvider {

    private static final String QUERY =
            "SELECT 1 FROM co_block b " +
            "JOIN co_world w ON b.wid = w.id " +
            "WHERE w.world = ? " +
            "  AND b.time > ? " +
            "  AND b.x BETWEEN ? AND ? " +
            "  AND b.z BETWEEN ? AND ? " +
            "LIMIT 1";

    private final Logger logger;

    /**
     * Path to the CoreProtect SQLite database.
     * {@code null} only in the behaviour-stub test constructor.
     */
    private final File dbFile;

    /**
     * Used only by the {@link #CoreProtectProvider(boolean)} test-stub constructor
     * to short-circuit {@link #isAvailable()} without a real file.
     */
    private final boolean forcedAvailable;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor.
     *
     * @param logger     plugin logger
     * @param serverRoot the server's working directory (the folder that
     *                   contains {@code plugins/})
     */
    CoreProtectProvider(Logger logger, File serverRoot) {
        this.logger = logger;
        this.dbFile = new File(serverRoot, "plugins/CoreProtect/database.db");
        this.forcedAvailable = false;
        if (dbFile.exists()) {
            logger.info("[WorldPrune] CoreProtect integration active (direct SQLite query).");
        } else {
            logger.fine("[WorldPrune] CoreProtect DB not found at " + dbFile
                    + " — integration skipped.");
        }
    }

    /**
     * Test constructor that points at an explicit database file.
     * Allows {@link CoreProtectProviderTest} to use a real temp SQLite DB
     * without needing a running Bukkit server.
     *
     * @param logger  plugin logger
     * @param dbFile  path to the SQLite file to query
     * @param unused  distinguishing marker to avoid signature clash with the
     *                behaviour-stub constructor
     */
    CoreProtectProvider(Logger logger, File dbFile, boolean unused) {
        this.logger = logger;
        this.dbFile = dbFile;
        this.forcedAvailable = false;
    }

    /**
     * Behaviour-stub constructor for unit tests that want to control return
     * values via subclass overrides without touching the filesystem.
     * Used by {@link HeuristicServiceTest}.
     */
    CoreProtectProvider(boolean forcedAvailable) {
        this.logger = Logger.getLogger(CoreProtectProvider.class.getName());
        this.dbFile = null;
        this.forcedAvailable = forcedAvailable;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    boolean isAvailable() {
        if (dbFile == null) return forcedAvailable;   // test-stub path
        return dbFile.exists();
    }

    /**
     * Returns {@code true} if CoreProtect recorded at least one player block
     * action anywhere inside the given region within the last
     * {@code lookbackDays} days.
     *
     * <p>Region {@code (regionX, regionZ)} covers blocks
     * {@code [regionX*512 .. regionX*512+511] × [regionZ*512 .. regionZ*512+511]}.
     *
     * <p>Returns {@code true} on any error (fail-safe: unknown → keep the region)
     * so that a corrupt, locked, or unreadable DB never causes active regions to
     * be pruned.  A WARNING is logged whenever the fallback is triggered.
     */
    /**
     * Checks multiple regions in a single DB connection.
     *
     * <p>Executes the same per-region query as {@link #hasRecentActivity} but
     * reuses one {@link Connection} and one {@link PreparedStatement} across
     * all candidates, avoiding the per-call connection overhead of the single
     * variant.
     *
     * <p>Fail-safe: if any exception occurs the entire candidate set is returned
     * (treat-as-active), consistent with the single-region behaviour.
     *
     * @param worldName    CoreProtect world name
     * @param candidates   map of region filename → {@code [regionX, regionZ]}
     * @param lookbackDays activity window in days
     * @return subset of {@code candidates} keys that have recent activity
     */
    Set<String> findRegionsWithRecentActivity(
            String worldName, Map<String, int[]> candidates, int lookbackDays) {
        Set<String> active = new HashSet<>();
        if (!isAvailable() || candidates.isEmpty()) {
            return active;
        }
        long since = System.currentTimeMillis() / 1000L - (long) lookbackDays * 86400;
        try (Connection conn = DriverManager.getConnection(
                     "jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(QUERY)) {
            ps.setString(1, worldName);
            ps.setLong(2, since);
            for (Map.Entry<String, int[]> entry : candidates.entrySet()) {
                int[] coords = entry.getValue();
                int minX = coords[0] * 512;
                int minZ = coords[1] * 512;
                ps.setInt(3, minX);
                ps.setInt(4, minX + 511);
                ps.setInt(5, minZ);
                ps.setInt(6, minZ + 511);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        active.add(entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[WorldPrune] CoreProtect batch query error for "
                    + candidates.size() + " regions in world '" + worldName
                    + "' — treating all as active (fail-safe): " + e.getMessage());
            return new HashSet<>(candidates.keySet());
        }
        return active;
    }

    boolean hasRecentActivity(String worldName, int regionX, int regionZ, int lookbackDays) {
        if (!isAvailable()) return false;
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            long since = System.currentTimeMillis() / 1000L - (long) lookbackDays * 86400;
            int minX = regionX * 512;
            int maxX = minX + 511;
            int minZ = regionZ * 512;
            int maxZ = minZ + 511;
            try (PreparedStatement ps = conn.prepareStatement(QUERY)) {
                ps.setString(1, worldName);
                ps.setLong(2, since);
                ps.setInt(3, minX);
                ps.setInt(4, maxX);
                ps.setInt(5, minZ);
                ps.setInt(6, maxZ);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception e) {
            logger.warning("[WorldPrune] CoreProtect query error for region ("
                    + regionX + "," + regionZ + ") — treating as active to avoid accidental pruning: "
                    + e.getMessage());
            return true;
        }
    }
}
