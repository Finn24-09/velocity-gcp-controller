package io.github.finn2409.velocityGcpController.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PluginConfig {
    // Modules
    private boolean gcpEnabled;
    private boolean idleManagementEnabled;
    private boolean whitelistEnabled;
    private boolean commandsEnabled;

    // GCP Configuration
    private String projectId;
    private String instanceName;
    private String zone;
    private boolean useServiceAccount;
    private String serviceAccountKey;
    private int statusCacheSeconds;
    private int startupCooldownMinutes;
    private ShutdownMode shutdownMode;

    // Timers
    private int idleShutdownMinutes;
    private int startupTimeoutMinutes;

    // Backend
    private String backendAddress;
    private int backendPort;
    private String serverName;

    // Whitelist
    private String whitelistFile;
    private String kickMessage;

    // Commands
    private List<String> authorizedUuids;
    private boolean pingEnabled;
    private boolean vwhitelistEnabled;

    // Messages
    private String serverStartingMessage;
    private String startupFailedMessage;
    private String gcpDisabledMessage;

    // Logging
    private boolean logConnections;
    private boolean logGcpCommands;
    private boolean logWhitelistChecks;
    private boolean logTimerEvents;

    // Tracks an invalid raw shutdown-mode value so getWarnings() can surface it
    private String invalidShutdownModeValue;

    public PluginConfig() {
        // Default values
        this.gcpEnabled = true;
        this.idleManagementEnabled = true;
        this.whitelistEnabled = true;
        this.commandsEnabled = true;

        this.projectId = "your-project-id";
        this.instanceName = "minecraft-server";
        this.zone = "us-central1-a";
        this.useServiceAccount = true;
        this.serviceAccountKey = "path/to/key.json";
        this.statusCacheSeconds = 10;
        this.startupCooldownMinutes = 5;
        this.shutdownMode = ShutdownMode.STOP;

        this.idleShutdownMinutes = 30;
        this.startupTimeoutMinutes = 15;

        this.backendAddress = "localhost";
        this.backendPort = 25565;
        this.serverName = "lobby";

        this.whitelistFile = "whitelist.json";
        this.kickMessage = "You are not whitelisted on this server.";

        this.authorizedUuids = new ArrayList<>();
        this.authorizedUuids.add("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        this.pingEnabled = true;
        this.vwhitelistEnabled = true;

        this.serverStartingMessage = "&eServer is starting! &aPlease reconnect in 30-60 seconds.";
        this.startupFailedMessage = "&cFailed to start server. Please contact an administrator.";
        this.gcpDisabledMessage = "&cBackend server is offline and auto-start is disabled.";

        this.logConnections = true;
        this.logGcpCommands = true;
        this.logWhitelistChecks = false;
        this.logTimerEvents = true;
    }

    public static PluginConfig load(Path configPath) throws IOException {
        PluginConfig config = new PluginConfig();

        // Create default config if it doesn't exist
        if (!Files.exists(configPath)) {
            config.save(configPath);
            return config;
        }

        // Read with explicit UTF-8, then sanitize before handing to SnakeYAML.
        // Sanitization fixes two real-world failure modes that produced
        // "expected '<document start>'" parser errors:
        //   1. Editors that prepend a UTF-8 BOM (﻿).
        //   2. Stray "%YAML" / "%TAG" directive lines or "---" markers added
        //      manually by users — these flip SnakeYAML into the explicit
        //      document path and break parsing of the first key.
        String raw = Files.readString(configPath, StandardCharsets.UTF_8);
        String yamlSource = sanitizeYaml(raw);

        Map<String, Object> data;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            data = yaml.load(yamlSource);
        } catch (RuntimeException e) {
            throw new IOException(
                "Failed to parse config.yml at " + configPath
                    + ". Delete the file to regenerate defaults, or check for syntax errors. "
                    + "First lines of file:\n" + previewFirstLines(yamlSource, 8),
                e);
        }

        if (data == null) {
            return config;
        }

        // Parse modules
        Map<String, Object> modules = getMap(data, "modules");
        if (modules != null) {
            config.gcpEnabled = getBoolean(modules, "gcp", true);
            config.idleManagementEnabled = getBoolean(modules, "idle-management", true);
            config.whitelistEnabled = getBoolean(modules, "whitelist", true);
            config.commandsEnabled = getBoolean(modules, "commands", true);
        }

        // Parse GCP config
        Map<String, Object> gcp = getMap(data, "gcp");
        if (gcp != null) {
            config.projectId = getString(gcp, "project-id", "your-project-id");
            config.instanceName = getString(gcp, "instance-name", "minecraft-atm10");
            config.zone = getString(gcp, "zone", "us-central1-a");
            config.useServiceAccount = getBoolean(gcp, "use-service-account", true);
            config.serviceAccountKey = getString(gcp, "service-account-key", "path/to/key.json");
            config.statusCacheSeconds = getInt(gcp, "status-cache-seconds", 10);
            config.startupCooldownMinutes = getInt(gcp, "startup-cooldown-minutes", 5);

            String rawShutdownMode = getString(gcp, "shutdown-mode", "stop");
            ShutdownMode parsedMode = ShutdownMode.parse(rawShutdownMode, ShutdownMode.STOP);
            if (parsedMode == ShutdownMode.STOP
                && rawShutdownMode != null
                && !rawShutdownMode.trim().isEmpty()
                && !rawShutdownMode.trim().equalsIgnoreCase("stop")) {
                config.invalidShutdownModeValue = rawShutdownMode;
            }
            config.shutdownMode = parsedMode;
        }

        // Parse timers
        Map<String, Object> timers = getMap(data, "timers");
        if (timers != null) {
            config.idleShutdownMinutes = getInt(timers, "idle-shutdown-minutes", 30);
            config.startupTimeoutMinutes = getInt(timers, "startup-timeout-minutes", 15);
        }

        // Parse backend
        Map<String, Object> backend = getMap(data, "backend");
        if (backend != null) {
            config.backendAddress = getString(backend, "address", "localhost");
            config.backendPort = getInt(backend, "port", 25565);
            config.serverName = getString(backend, "server-name", "atm10");
        }

        // Parse whitelist
        Map<String, Object> whitelist = getMap(data, "whitelist");
        if (whitelist != null) {
            config.whitelistFile = getString(whitelist, "file", "whitelist.json");
            config.kickMessage = getString(whitelist, "kick-message", "You are not whitelisted on this server.");
        }

        // Parse commands
        Map<String, Object> commands = getMap(data, "commands");
        if (commands != null) {
            Object uuids = commands.get("authorized-uuids");
            if (uuids instanceof List) {
                config.authorizedUuids = new ArrayList<>();
                for (Object uuid : (List<?>) uuids) {
                    config.authorizedUuids.add(uuid.toString());
                }
            }
            config.pingEnabled = getBoolean(commands, "ping-enabled", true);
            config.vwhitelistEnabled = getBoolean(commands, "vwhitelist-enabled", true);
        }

        // Parse messages
        Map<String, Object> messages = getMap(data, "messages");
        if (messages != null) {
            config.serverStartingMessage = getString(messages, "server-starting", "&eServer is starting! &aPlease reconnect in 2-3 minutes.");
            config.startupFailedMessage = getString(messages, "startup-failed", "&cFailed to start server. Please contact an administrator.");
            config.gcpDisabledMessage = getString(messages, "gcp-disabled", "&cBackend server is offline and auto-start is disabled.");
        }

        // Parse logging
        Map<String, Object> logging = getMap(data, "logging");
        if (logging != null) {
            config.logConnections = getBoolean(logging, "log-connections", true);
            config.logGcpCommands = getBoolean(logging, "log-gcp-commands", true);
            config.logWhitelistChecks = getBoolean(logging, "log-whitelist-checks", false);
            config.logTimerEvents = getBoolean(logging, "log-timer-events", true);
        }

        return config;
    }

    /**
     * Remove a UTF-8 BOM and any leading YAML directives or document markers
     * that would otherwise force SnakeYAML into the explicit-document parser
     * path. The plugin's {@link #save(Path)} method never emits these, so any
     * present in a user-edited file are accidental and safe to drop.
     */
    static String sanitizeYaml(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // Strip UTF-8 BOM (U+FEFF) if present.
        if (input.charAt(0) == 0xFEFF) {
            input = input.substring(1);
        }

        StringBuilder out = new StringBuilder(input.length());
        boolean inHeader = true;
        for (String line : input.split("\\R", -1)) {
            if (inHeader) {
                String stripped = line.stripLeading();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    out.append(line).append('\n');
                    continue;
                }
                // Drop directives ("%YAML 1.2", "%TAG !x! tag:") and document
                // start/end markers. These flip SnakeYAML into a state where
                // the very next mapping triggers
                // "expected '<document start>'" — the symptom we are guarding
                // against. Replacing them with a comment preserves line
                // numbers in any future error messages.
                if (stripped.startsWith("%") || stripped.equals("---") || stripped.equals("...")) {
                    out.append("# ").append(line).append('\n');
                    continue;
                }
                inHeader = false;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String previewFirstLines(String input, int maxLines) {
        if (input == null) return "(empty)";
        String[] lines = input.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxLines, lines.length);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("  %3d| %s%n", i + 1, lines[i]));
        }
        if (lines.length > limit) {
            sb.append("  ... (").append(lines.length - limit).append(" more lines)\n");
        }
        return sb.toString();
    }

    public void save(Path configPath) throws IOException {
        Files.createDirectories(configPath.getParent());

        StringBuilder sb = new StringBuilder();
        sb.append("# Velocity GCP Controller Configuration\n");
        sb.append("# For detailed documentation, see: https://github.com/Finn24-09/velocity-gcp-controller\n\n");

        // Modules
        sb.append("# Module Control - Enable or disable features\n");
        sb.append("modules:\n");
        sb.append("  gcp: ").append(gcpEnabled).append("                    # GCP instance management (requires idle-management)\n");
        sb.append("  idle-management: ").append(idleManagementEnabled).append("        # Automatic shutdown timers (requires gcp)\n");
        sb.append("  whitelist: ").append(whitelistEnabled).append("              # Proxy-level whitelist system\n");
        sb.append("  commands: ").append(commandsEnabled).append("               # Enable /ping and /vwhitelist commands\n\n");

        // GCP
        sb.append("# Google Cloud Platform Configuration\n");
        sb.append("gcp:\n");
        sb.append("  project-id: \"").append(yamlEscape(projectId)).append("\"              # Your GCP project ID\n");
        sb.append("  instance-name: \"").append(yamlEscape(instanceName)).append("\"           # Name of the Compute Engine instance\n");
        sb.append("  zone: \"").append(yamlEscape(zone)).append("\"                      # GCP zone (e.g., us-central1-a, europe-west1-b)\n");
        sb.append("  use-service-account: ").append(useServiceAccount).append("                  # Use instance service account (true) or JSON key (false)\n");
        sb.append("  service-account-key: \"").append(yamlEscape(serviceAccountKey)).append("\"    # Path to JSON key file (only if use-service-account: false)\n");
        sb.append("  status-cache-seconds: ").append(statusCacheSeconds).append("                   # How long to cache instance status (reduces API calls)\n");
        sb.append("  startup-cooldown-minutes: ").append(startupCooldownMinutes).append("                # Cooldown to prevent duplicate start commands\n");
        sb.append("  shutdown-mode: \"").append(shutdownMode.asConfigValue()).append("\"               # \"stop\" (default, full TERMINATE, cheapest) or \"suspend\" (save RAM to disk, ~10-30s resume vs 2-3min cold start; small ongoing cost for memory snapshot)\n\n");

        // Timers
        sb.append("# Timer Configuration\n");
        sb.append("timers:\n");
        sb.append("  idle-shutdown-minutes: ").append(idleShutdownMinutes).append("       # Time to wait after last player leaves before shutdown\n");
        sb.append("  startup-timeout-minutes: ").append(startupTimeoutMinutes).append("     # Time to wait for players to join after startup (prevents orphaned instances)\n\n");

        // Backend
        sb.append("# Backend Server Configuration\n");
        sb.append("backend:\n");
        sb.append("  address: \"").append(yamlEscape(backendAddress)).append("\"            # Backend server address\n");
        sb.append("  port: ").append(backendPort).append("                     # Backend server port\n");
        sb.append("  server-name: \"").append(yamlEscape(serverName)).append("\"            # Server name as defined in velocity.toml\n\n");

        // Whitelist
        sb.append("# Whitelist Configuration\n");
        sb.append("whitelist:\n");
        sb.append("  file: \"").append(yamlEscape(whitelistFile)).append("\"                                # Whitelist file name (Minecraft format)\n");
        sb.append("  kick-message: \"").append(yamlEscape(kickMessage)).append("\"  # Message for non-whitelisted players\n\n");

        // Commands
        sb.append("# Commands Configuration\n");
        sb.append("commands:\n");
        sb.append("  authorized-uuids:               # Player UUIDs authorized to use /vwhitelist\n");
        for (String uuid : authorizedUuids) {
            sb.append("    - \"").append(yamlEscape(uuid)).append("\"  # Replace with your UUID\n");
        }
        sb.append("  ping-enabled: ").append(pingEnabled).append("              # Enable /ping command\n");
        sb.append("  vwhitelist-enabled: ").append(vwhitelistEnabled).append("        # Enable /vwhitelist commands\n\n");

        // Messages
        sb.append("# Player Messages (supports & color codes)\n");
        sb.append("messages:\n");
        sb.append("  server-starting: \"").append(yamlEscape(serverStartingMessage)).append("\"\n");
        sb.append("  startup-failed: \"").append(yamlEscape(startupFailedMessage)).append("\"\n");
        sb.append("  gcp-disabled: \"").append(yamlEscape(gcpDisabledMessage)).append("\"\n\n");

        // Logging
        sb.append("# Logging Configuration\n");
        sb.append("logging:\n");
        sb.append("  log-connections: ").append(logConnections).append("           # Log player connections/disconnections\n");
        sb.append("  log-gcp-commands: ").append(logGcpCommands).append("          # Log GCP start/stop commands\n");
        sb.append("  log-whitelist-checks: ").append(logWhitelistChecks).append("     # Log whitelist check results (can be verbose)\n");
        sb.append("  log-timer-events: ").append(logTimerEvents).append("          # Log timer start/cancel/expiry events\n");

        Files.writeString(configPath, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Escape a value going into a double-quoted YAML scalar. Without this,
     * a kick message containing {@code "} would close the scalar early and
     * produce an invalid file that fails to parse on the next plugin load.
     */
    static String yamlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // Critical validations
        if (gcpEnabled != idleManagementEnabled) {
            errors.add("CRITICAL: gcp and idle-management modules must match (both true or both false)");
        }

        if (gcpEnabled) {
            if (projectId == null || projectId.equals("your-project-id")) {
                errors.add("CRITICAL: GCP project-id must be configured when gcp module is enabled");
            }
            if (instanceName == null || instanceName.isEmpty()) {
                errors.add("CRITICAL: GCP instance-name must be configured when gcp module is enabled");
            }
            if (zone == null || zone.isEmpty()) {
                errors.add("CRITICAL: GCP zone must be configured when gcp module is enabled");
            }
            if (!useServiceAccount && (serviceAccountKey == null || serviceAccountKey.equals("path/to/key.json"))) {
                errors.add("CRITICAL: service-account-key must be configured when use-service-account is false");
            }
        }

        return errors;
    }

    public List<String> getWarnings() {
        List<String> warnings = new ArrayList<>();

        if (vwhitelistEnabled && !whitelistEnabled) {
            warnings.add("WARNING: vwhitelist commands are enabled but whitelist module is disabled");
        }

        if (authorizedUuids.isEmpty()) {
            warnings.add("WARNING: authorized-uuids list is empty - no one can use /vwhitelist commands");
        }

        if (!gcpEnabled && (idleShutdownMinutes > 0 || startupTimeoutMinutes > 0)) {
            warnings.add("WARNING: Timer settings configured but gcp module is disabled - settings will be ignored");
        }

        if (invalidShutdownModeValue != null) {
            warnings.add("WARNING: shutdown-mode value '" + invalidShutdownModeValue
                + "' is invalid. Must be 'stop' or 'suspend'. Defaulting to 'stop'.");
        }

        return warnings;
    }

    // Helper methods
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    // Getters
    public boolean isGcpEnabled() { return gcpEnabled; }
    public boolean isIdleManagementEnabled() { return idleManagementEnabled; }
    public boolean isWhitelistEnabled() { return whitelistEnabled; }
    public boolean isCommandsEnabled() { return commandsEnabled; }

    public String getProjectId() { return projectId; }
    public String getInstanceName() { return instanceName; }
    public String getZone() { return zone; }
    public boolean isUseServiceAccount() { return useServiceAccount; }
    public String getServiceAccountKey() { return serviceAccountKey; }
    public int getStatusCacheSeconds() { return statusCacheSeconds; }
    public int getStartupCooldownMinutes() { return startupCooldownMinutes; }
    public ShutdownMode getShutdownMode() { return shutdownMode; }

    public int getIdleShutdownMinutes() { return idleShutdownMinutes; }
    public int getStartupTimeoutMinutes() { return startupTimeoutMinutes; }

    public String getBackendAddress() { return backendAddress; }
    public int getBackendPort() { return backendPort; }
    public String getServerName() { return serverName; }

    public String getWhitelistFile() { return whitelistFile; }
    public String getKickMessage() { return kickMessage; }

    public List<String> getAuthorizedUuids() { return authorizedUuids; }
    public boolean isPingEnabled() { return pingEnabled; }
    public boolean isVwhitelistEnabled() { return vwhitelistEnabled; }

    public String getServerStartingMessage() { return serverStartingMessage; }
    public String getStartupFailedMessage() { return startupFailedMessage; }
    public String getGcpDisabledMessage() { return gcpDisabledMessage; }

    public boolean isLogConnections() { return logConnections; }
    public boolean isLogGcpCommands() { return logGcpCommands; }
    public boolean isLogWhitelistChecks() { return logWhitelistChecks; }
    public boolean isLogTimerEvents() { return logTimerEvents; }
}
