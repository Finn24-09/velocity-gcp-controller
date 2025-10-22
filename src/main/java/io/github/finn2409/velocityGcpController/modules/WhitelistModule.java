package io.github.finn2409.velocityGcpController.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WhitelistModule implements Module {
    private final PluginConfig config;
    private final Logger logger;
    private final Path dataDirectory;
    private final Gson gson;

    private Set<UUID> whitelistedPlayers;
    private Map<UUID, String> playerNames;  // UUID -> Player Name mapping
    private Path whitelistPath;

    public WhitelistModule(PluginConfig config, Logger logger, Path dataDirectory) {
        this.config = config;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.whitelistedPlayers = new HashSet<>();
        this.playerNames = new HashMap<>();
    }

    @Override
    public void initialize() throws Exception {
        if (!config.isWhitelistEnabled()) {
            return;
        }

        logger.info("[VelocityGCPController] Initializing Whitelist module...");

        whitelistPath = dataDirectory.resolve(config.getWhitelistFile());

        if (!Files.exists(whitelistPath)) {
            // Create default whitelist with example entry
            addExampleEntry();
            saveWhitelist();
            logger.info("[VelocityGCPController] Created default whitelist file with example entry at: {}", whitelistPath);
        } else {
            loadWhitelist();
            logger.info("[VelocityGCPController] Loaded {} whitelisted players", whitelistedPlayers.size());
        }
    }

    /**
     * Add an example entry to the whitelist
     */
    private void addExampleEntry() {
        // Add Steve as an example (using a well-known UUID)
        UUID exampleUuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        whitelistedPlayers.add(exampleUuid);
        playerNames.put(exampleUuid, "ExamplePlayer");
    }

    @Override
    public void shutdown() {
        logger.info("[VelocityGCPController] Whitelist module shut down");
    }

    @Override
    public String getName() {
        return "Whitelist";
    }

    @Override
    public boolean isEnabled() {
        return config.isWhitelistEnabled();
    }

    /**
     * Check if a player is whitelisted
     */
    public boolean isWhitelisted(UUID uuid) {
        return whitelistedPlayers.contains(uuid);
    }

    /**
     * Add a player to the whitelist
     */
    public boolean addPlayer(UUID uuid, String name) {
        if (whitelistedPlayers.add(uuid)) {
            playerNames.put(uuid, name);
            try {
                saveWhitelist();
                logger.info("[VelocityGCPController] Added {} ({}) to whitelist", name, uuid);
                return true;
            } catch (IOException e) {
                logger.error("[VelocityGCPController] Failed to save whitelist", e);
                whitelistedPlayers.remove(uuid);
                playerNames.remove(uuid);
                return false;
            }
        }
        return false;
    }

    /**
     * Remove a player from the whitelist
     */
    public boolean removePlayer(UUID uuid, String name) {
        if (whitelistedPlayers.remove(uuid)) {
            playerNames.remove(uuid);
            try {
                saveWhitelist();
                logger.info("[VelocityGCPController] Removed {} ({}) from whitelist", name, uuid);
                return true;
            } catch (IOException e) {
                logger.error("[VelocityGCPController] Failed to save whitelist", e);
                whitelistedPlayers.add(uuid);
                playerNames.put(uuid, name);
                return false;
            }
        }
        return false;
    }

    /**
     * Get all whitelisted players
     */
    public Set<UUID> getWhitelistedPlayers() {
        return new HashSet<>(whitelistedPlayers);
    }

    /**
     * Get player name by UUID
     */
    public String getPlayerName(UUID uuid) {
        return playerNames.getOrDefault(uuid, "Unknown");
    }

    /**
     * Load whitelist from file
     */
    private void loadWhitelist() throws IOException {
        String content = Files.readString(whitelistPath);
        List<WhitelistEntry> entries = gson.fromJson(content, new TypeToken<List<WhitelistEntry>>(){}.getType());

        whitelistedPlayers.clear();
        playerNames.clear();
        if (entries != null) {
            for (WhitelistEntry entry : entries) {
                try {
                    UUID uuid = UUID.fromString(entry.uuid);
                    whitelistedPlayers.add(uuid);
                    playerNames.put(uuid, entry.name);
                } catch (IllegalArgumentException e) {
                    logger.warn("[VelocityGCPController] Invalid UUID in whitelist: {}", entry.uuid);
                }
            }
        }
    }

    /**
     * Save whitelist to file
     */
    private void saveWhitelist() throws IOException {
        List<WhitelistEntry> entries = new ArrayList<>();
        for (UUID uuid : whitelistedPlayers) {
            String name = playerNames.getOrDefault(uuid, "Unknown");
            entries.add(new WhitelistEntry(uuid.toString(), name));
        }

        Files.createDirectories(whitelistPath.getParent());
        String json = gson.toJson(entries);
        Files.writeString(whitelistPath, json);
    }

    /**
     * Whitelist entry structure matching Minecraft's format
     */
    private static class WhitelistEntry {
        private final String uuid;
        private final String name;

        public WhitelistEntry(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}
