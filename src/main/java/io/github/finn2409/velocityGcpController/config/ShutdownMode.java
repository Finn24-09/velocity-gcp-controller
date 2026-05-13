package io.github.finn2409.velocityGcpController.config;

public enum ShutdownMode {
    STOP,
    SUSPEND;

    public static ShutdownMode parse(String value, ShutdownMode fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return switch (trimmed.toLowerCase()) {
            case "stop" -> STOP;
            case "suspend" -> SUSPEND;
            default -> fallback;
        };
    }

    public String asConfigValue() {
        return name().toLowerCase();
    }
}
