package dev.minecraft.prune;

enum HeuristicMode {
    SIZE("size"),
    ENTITY_AWARE("entity-aware");

    private final String cli;

    HeuristicMode(String cli) {
        this.cli = cli;
    }

    String cli() {
        return cli;
    }

    static HeuristicMode fromString(String value) {
        if (value == null) return ENTITY_AWARE;
        for (HeuristicMode mode : values()) {
            if (mode.cli.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value.replace('-', '_'))) {
                return mode;
            }
        }
        return ENTITY_AWARE;
    }
}
