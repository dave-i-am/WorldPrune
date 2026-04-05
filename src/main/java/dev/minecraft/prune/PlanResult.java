package dev.minecraft.prune;

import java.io.File;

public record PlanResult(String planId, String worldName, File reportDir, String claimSource, String confirmToken) {
}
