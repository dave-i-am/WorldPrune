package dev.minecraft.prune;

import java.io.File;

public record HeuristicResult(String runId, String worldName, String mode, int keepCount, int pruneCount, double reclaimableGiBEstimate, File reportDir, String confirmToken) {
}
