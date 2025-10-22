package io.github.finn2409.velocityGcpController.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import io.github.finn2409.velocityGcpController.util.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CommandsModule implements Module {
    private final PluginConfig config;
    private final Logger logger;
    private final ProxyServer server;
    private final WhitelistModule whitelistModule;
    private final OkHttpClient httpClient;

    public CommandsModule(PluginConfig config, Logger logger, ProxyServer server, WhitelistModule whitelistModule) {
        this.config = config;
        this.logger = logger;
        this.server = server;
        this.whitelistModule = whitelistModule;
        this.httpClient = new OkHttpClient();
    }

    @Override
    public void initialize() throws Exception {
        if (!config.isCommandsEnabled()) {
            return;
        }

        logger.info("[VelocityGCPController] Initializing Commands module...");

        CommandManager commandManager = server.getCommandManager();

        if (config.isPingEnabled()) {
            CommandMeta pingMeta = commandManager.metaBuilder("ping")
                .build();
            commandManager.register(pingMeta, new PingCommand());
            logger.info("[VelocityGCPController] Registered /ping command");
        }

        if (config.isVwhitelistEnabled()) {
            CommandMeta vwhitelistMeta = commandManager.metaBuilder("vwhitelist")
                .build();
            commandManager.register(vwhitelistMeta, new VWhitelistCommand());
            logger.info("[VelocityGCPController] Registered /vwhitelist command");
        }
    }

    @Override
    public void shutdown() {
        logger.info("[VelocityGCPController] Commands module shut down");
    }

    @Override
    public String getName() {
        return "Commands";
    }

    @Override
    public boolean isEnabled() {
        return config.isCommandsEnabled();
    }

    /**
     * /ping command implementation
     */
    private class PingCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();

            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
                return;
            }

            Player player = (Player) source;
            long ping = player.getPing();

            // Determine color based on ping
            String colorCode;
            if (ping <= 50) {
                colorCode = "&2"; // Dark Green
            } else if (ping <= 100) {
                colorCode = "&a"; // Green
            } else if (ping <= 150) {
                colorCode = "&e"; // Yellow
            } else if (ping <= 200) {
                colorCode = "&6"; // Gold
            } else if (ping <= 300) {
                colorCode = "&c"; // Red
            } else {
                colorCode = "&4"; // Dark Red
            }

            String message = "Your ping: " + colorCode + ping + "ms";
            player.sendMessage(MessageFormatter.format(message));
        }
    }

    /**
     * /vwhitelist command implementation
     */
    private class VWhitelistCommand implements SimpleCommand {
        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();

            // Suggest subcommands if no args or only partial first arg
            if (args.length == 0 || args.length == 1) {
                List<String> suggestions = new java.util.ArrayList<>();
                String partial = args.length == 1 ? args[0].toLowerCase() : "";

                if ("add".startsWith(partial)) suggestions.add("add");
                if ("remove".startsWith(partial)) suggestions.add("remove");
                if ("list".startsWith(partial)) suggestions.add("list");

                return suggestions;
            }

            // For "remove" subcommand, suggest whitelisted player names
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                List<String> suggestions = new java.util.ArrayList<>();
                String partial = args[1].toLowerCase();

                for (UUID uuid : whitelistModule.getWhitelistedPlayers()) {
                    String name = whitelistModule.getPlayerName(uuid);
                    if (name != null && name.toLowerCase().startsWith(partial)) {
                        suggestions.add(name);
                    }
                }

                return suggestions;
            }

            return List.of();
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
                return;
            }

            Player player = (Player) source;

            // Check authorization
            if (!config.getAuthorizedUuids().contains(player.getUniqueId().toString())) {
                source.sendMessage(Component.text("You do not have permission to use this command.").color(NamedTextColor.RED));
                logger.warn("[VelocityGCPController] Player {} attempted to use /vwhitelist without authorization", player.getUsername());
                return;
            }

            if (args.length == 0) {
                source.sendMessage(Component.text("Usage: /vwhitelist <add|remove|list> [playername]").color(NamedTextColor.YELLOW));
                return;
            }

            String subcommand = args[0].toLowerCase();

            switch (subcommand) {
                case "add":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /vwhitelist add <playername>").color(NamedTextColor.YELLOW));
                        return;
                    }
                    handleAdd(player, args[1]);
                    break;

                case "remove":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /vwhitelist remove <playername>").color(NamedTextColor.YELLOW));
                        return;
                    }
                    handleRemove(player, args[1]);
                    break;

                case "list":
                    handleList(player);
                    break;

                default:
                    source.sendMessage(Component.text("Unknown subcommand. Usage: /vwhitelist <add|remove|list> [playername]").color(NamedTextColor.YELLOW));
                    break;
            }
        }

        private void handleAdd(Player executor, String playerName) {
            executor.sendMessage(Component.text("Looking up player...").color(NamedTextColor.GRAY));

            CompletableFuture.supplyAsync(() -> lookupPlayerUUID(playerName))
                .thenAccept(uuid -> {
                    if (uuid == null) {
                        executor.sendMessage(Component.text("Player not found or invalid name.").color(NamedTextColor.RED));
                        return;
                    }

                    if (whitelistModule.addPlayer(uuid, playerName)) {
                        executor.sendMessage(Component.text("Added " + playerName + " to the whitelist.").color(NamedTextColor.GREEN));
                        logger.info("[VelocityGCPController] Player {} added {} ({}) to whitelist", executor.getUsername(), playerName, uuid);
                    } else {
                        executor.sendMessage(Component.text("Player is already whitelisted or failed to add.").color(NamedTextColor.YELLOW));
                    }
                });
        }

        private void handleRemove(Player executor, String playerName) {
            executor.sendMessage(Component.text("Looking up player...").color(NamedTextColor.GRAY));

            CompletableFuture.supplyAsync(() -> lookupPlayerUUID(playerName))
                .thenAccept(uuid -> {
                    if (uuid == null) {
                        executor.sendMessage(Component.text("Player not found or invalid name.").color(NamedTextColor.RED));
                        return;
                    }

                    if (whitelistModule.removePlayer(uuid, playerName)) {
                        executor.sendMessage(Component.text("Removed " + playerName + " from the whitelist.").color(NamedTextColor.GREEN));
                        logger.info("[VelocityGCPController] Player {} removed {} ({}) from whitelist", executor.getUsername(), playerName, uuid);
                    } else {
                        executor.sendMessage(Component.text("Player is not whitelisted or failed to remove.").color(NamedTextColor.YELLOW));
                    }
                });
        }

        private void handleList(Player executor) {
            if (whitelistModule.getWhitelistedPlayers().isEmpty()) {
                executor.sendMessage(Component.text("The whitelist is empty.").color(NamedTextColor.YELLOW));
                return;
            }

            executor.sendMessage(Component.text("Whitelisted players (" + whitelistModule.getWhitelistedPlayers().size() + "):").color(NamedTextColor.GREEN));
            for (UUID uuid : whitelistModule.getWhitelistedPlayers()) {
                String name = whitelistModule.getPlayerName(uuid);
                if (name != null && !name.equals("Unknown")) {
                    executor.sendMessage(Component.text("  - " + name + " (" + uuid + ")").color(NamedTextColor.GRAY));
                } else {
                    executor.sendMessage(Component.text("  - " + uuid.toString()).color(NamedTextColor.GRAY));
                }
            }
        }

        /**
         * Look up player UUID from Mojang API
         */
        private UUID lookupPlayerUUID(String playerName) {
            try {
                String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
                Request request = new Request.Builder()
                    .url(url)
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        String uuidString = obj.get("id").getAsString();

                        // Convert from compact format to standard UUID format
                        String formatted = uuidString.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                            "$1-$2-$3-$4-$5"
                        );
                        return UUID.fromString(formatted);
                    }
                }
            } catch (IOException e) {
                logger.error("[VelocityGCPController] Failed to lookup player UUID for: {}", playerName, e);
            }
            return null;
        }
    }
}
