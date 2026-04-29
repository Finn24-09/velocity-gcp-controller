package io.github.finn2409.velocityGcpController;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import io.github.finn2409.velocityGcpController.modules.*;
import io.github.finn2409.velocityGcpController.util.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Plugin(id = "velocity-gcp-controller", name = "velocity-gcp-controller", version = BuildConstants.VERSION, description = "A Velocity proxy plugin designed to manage Google Cloud Platform Compute Engine instances for Minecraft servers, enabling automatic startup/shutdown.", url = "https://github.com/Finn24-09/velocity-gcp-controller", authors = {"Finn24-09"})
public class VelocityGcpController {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private GcpModule gcpModule;
    private WhitelistModule whitelistModule;
    private IdleManagementModule idleManagementModule;
    private CommandsModule commandsModule;

    @Inject
    public VelocityGcpController(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("[VelocityGCPController] Initializing plugin...");

        try {
            // Load configuration
            Path configPath = dataDirectory.resolve("config.yml");
            config = PluginConfig.load(configPath);

            // Validate configuration
            List<String> errors = config.validate();
            if (!errors.isEmpty()) {
                logger.error("[VelocityGCPController] Configuration validation failed:");
                for (String error : errors) {
                    logger.error("[VelocityGCPController]   {}", error);
                }
                logger.error("[VelocityGCPController] Plugin initialization aborted due to configuration errors");
                return;
            }

            // Show warnings
            List<String> warnings = config.getWarnings();
            for (String warning : warnings) {
                logger.warn("[VelocityGCPController] {}", warning);
            }

            // Initialize modules
            gcpModule = new GcpModule(config, logger);
            whitelistModule = new WhitelistModule(config, logger, dataDirectory);
            idleManagementModule = new IdleManagementModule(config, logger, server, this, gcpModule);
            commandsModule = new CommandsModule(config, logger, server, whitelistModule);

            // Start modules
            if (config.isGcpEnabled()) {
                gcpModule.initialize();
            }

            if (config.isWhitelistEnabled()) {
                whitelistModule.initialize();
            }

            if (config.isIdleManagementEnabled()) {
                idleManagementModule.initialize();
            }

            if (config.isCommandsEnabled()) {
                commandsModule.initialize();
            }

            logger.info("[VelocityGCPController] Plugin initialized successfully!");
            logger.info("[VelocityGCPController] Modules enabled: GCP={}, Idle={}, Whitelist={}, Commands={}",
                config.isGcpEnabled(), config.isIdleManagementEnabled(),
                config.isWhitelistEnabled(), config.isCommandsEnabled());

        } catch (Exception e) {
            logger.error("[VelocityGCPController] Failed to initialize plugin", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("[VelocityGCPController] Shutting down plugin...");

        if (commandsModule != null) commandsModule.shutdown();
        if (idleManagementModule != null) idleManagementModule.shutdown();
        if (whitelistModule != null) whitelistModule.shutdown();
        if (gcpModule != null) gcpModule.shutdown();

        logger.info("[VelocityGCPController] Plugin shut down successfully");
    }

    /**
     * Handle login event for whitelist checking.
     *
     * <p>LoginEvent fires after Mojang authentication has completed, so
     * {@link Player#getUniqueId()} is the authenticated Mojang UUID — the same
     * value stored in {@code whitelist.json} via the Mojang lookup performed by
     * {@code /vwhitelist add}. Earlier events (e.g. {@code PreLoginEvent}) only
     * expose the client-supplied UUID, which is null on Minecraft &lt;= 1.19.2
     * and is not guaranteed to match the authenticated UUID.</p>
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public void onLogin(LoginEvent event) {
        if (!config.isWhitelistEnabled() || whitelistModule == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!whitelistModule.isWhitelisted(player.getUniqueId())) {
            if (config.isLogWhitelistChecks()) {
                logger.info("[VelocityGCPController] Player {} failed whitelist check", player.getUsername());
            }
            event.setResult(ResultedEvent.ComponentResult.denied(
                MessageFormatter.format(config.getKickMessage())
            ));
        } else if (config.isLogWhitelistChecks()) {
            logger.info("[VelocityGCPController] Player {} passed whitelist check", player.getUsername());
        }
    }

    /**
     * Handle server pre-connect event for backend server startup logic
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> targetServer = event.getResult().getServer();

        if (!targetServer.isPresent()) {
            return;
        }

        // Check if this is our managed backend server
        String serverName = targetServer.get().getServerInfo().getName();
        if (!serverName.equals(config.getServerName())) {
            return;
        }

        if (config.isLogConnections()) {
            logger.info("[VelocityGCPController] Player {} attempting connection to backend server", player.getUsername());
        }

        // Check if GCP module is enabled
        if (!config.isGcpEnabled()) {
            if (config.isLogConnections()) {
                logger.info("[VelocityGCPController] GCP module disabled, allowing connection attempt");
            }
            return;
        }

        // Check server availability
        if (gcpModule.isServerAvailable()) {
            if (config.isLogConnections()) {
                logger.info("[VelocityGCPController] Backend server is available, allowing connection");
            }
            return;
        }

        // Server is not available - attempt to start it
        if (config.isLogConnections()) {
            logger.info("[VelocityGCPController] Backend server is offline, attempting to start");
        }

        // Deny connection first
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        // Send message and disconnect player from proxy
        player.disconnect(MessageFormatter.format(config.getServerStartingMessage()));

        if (config.isLogConnections()) {
            logger.info("[VelocityGCPController] Player {} disconnected with startup message", player.getUsername());
        }

        // Start the instance (respects cooldown internally)
        gcpModule.startInstance().thenAccept(commandSent -> {
            if (commandSent) {
                logger.info("[VelocityGCPController] Executed GCP start command for player {}", player.getUsername());

                // Start startup timeout timer
                if (config.isIdleManagementEnabled()) {
                    idleManagementModule.startStartupTimeout();
                }
            } else {
                logger.info("[VelocityGCPController] Startup cooldown active, skipped GCP command for player {}", player.getUsername());
            }
        });
    }

    /**
     * Handle successful server connection
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String serverName = event.getServer().getServerInfo().getName();

        // Check if this is our managed backend server
        if (!serverName.equals(config.getServerName())) {
            return;
        }

        if (config.isLogConnections()) {
            logger.info("[VelocityGCPController] Player {} connected to backend server", event.getPlayer().getUsername());
        }

        // Notify idle management module
        if (config.isIdleManagementEnabled()) {
            idleManagementModule.onPlayerJoin(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Handle player disconnect
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        // Check if this player was tracked (connected to our managed backend server)
        // We use the tracking system instead of getCurrentServer() because when a player
        // is kicked or disconnected by the backend server, getCurrentServer() returns empty
        if (!config.isIdleManagementEnabled()) {
            return;
        }

        if (idleManagementModule.isPlayerTracked(player.getUniqueId())) {
            if (config.isLogConnections()) {
                logger.info("[VelocityGCPController] Player {} disconnected from backend server", player.getUsername());
            }

            // Notify idle management module
            idleManagementModule.onPlayerLeave(player.getUniqueId());
        }
    }
}
