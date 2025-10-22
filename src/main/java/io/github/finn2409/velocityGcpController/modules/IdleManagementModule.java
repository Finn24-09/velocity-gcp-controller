package io.github.finn2409.velocityGcpController.modules;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IdleManagementModule implements Module {
    private final PluginConfig config;
    private final Logger logger;
    private final ProxyServer server;
    private final Object plugin;
    private final GcpModule gcpModule;

    private final AtomicInteger playerCount;
    private ScheduledTask idleShutdownTask;
    private ScheduledTask startupTimeoutTask;

    public IdleManagementModule(PluginConfig config, Logger logger, ProxyServer server, Object plugin, GcpModule gcpModule) {
        this.config = config;
        this.logger = logger;
        this.server = server;
        this.plugin = plugin;
        this.gcpModule = gcpModule;
        this.playerCount = new AtomicInteger(0);
    }

    @Override
    public void initialize() throws Exception {
        if (!config.isIdleManagementEnabled()) {
            return;
        }

        logger.info("[VelocityGCPController] Initializing Idle Management module...");
        logger.info("[VelocityGCPController] Idle shutdown timer: {} minutes", config.getIdleShutdownMinutes());
        logger.info("[VelocityGCPController] Startup timeout timer: {} minutes", config.getStartupTimeoutMinutes());
    }

    @Override
    public void shutdown() {
        cancelIdleShutdown();
        cancelStartupTimeout();
        logger.info("[VelocityGCPController] Idle Management module shut down");
    }

    @Override
    public String getName() {
        return "IdleManagement";
    }

    @Override
    public boolean isEnabled() {
        return config.isIdleManagementEnabled();
    }

    /**
     * Increment player count and cancel shutdown timer if active
     */
    public void onPlayerJoin() {
        int count = playerCount.incrementAndGet();

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Player joined backend, count: {}", count);
        }

        // Cancel timers when first player joins
        if (count == 1) {
            cancelIdleShutdown();
            cancelStartupTimeout();
        }
    }

    /**
     * Decrement player count and start shutdown timer if count reaches 0
     */
    public void onPlayerLeave() {
        int count = playerCount.decrementAndGet();

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Player left backend, count: {}", count);
        }

        // Start idle shutdown when last player leaves
        if (count == 0) {
            startIdleShutdown();
        }
    }

    /**
     * Start the idle shutdown timer
     */
    public void startIdleShutdown() {
        cancelIdleShutdown();

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Player count reached 0, starting idle shutdown timer ({} minutes)",
                config.getIdleShutdownMinutes());
        }

        idleShutdownTask = server.getScheduler()
            .buildTask(plugin, this::executeIdleShutdown)
            .delay(config.getIdleShutdownMinutes(), TimeUnit.MINUTES)
            .schedule();
    }

    /**
     * Cancel the idle shutdown timer
     */
    public void cancelIdleShutdown() {
        if (idleShutdownTask != null) {
            idleShutdownTask.cancel();
            idleShutdownTask = null;

            if (config.isLogTimerEvents()) {
                logger.info("[VelocityGCPController] Idle shutdown timer cancelled");
            }
        }
    }

    /**
     * Execute idle shutdown
     */
    private void executeIdleShutdown() {
        // Double check player count
        if (playerCount.get() > 0) {
            if (config.isLogTimerEvents()) {
                logger.info("[VelocityGCPController] Idle shutdown cancelled - players online");
            }
            return;
        }

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Idle shutdown timer expired, executing GCP stop command");
        }

        gcpModule.stopInstance().thenAccept(success -> {
            if (success) {
                logger.info("[VelocityGCPController] Idle shutdown completed successfully");
            } else {
                logger.error("[VelocityGCPController] Idle shutdown failed");
            }
        });
    }

    /**
     * Start the startup timeout timer
     */
    public void startStartupTimeout() {
        cancelStartupTimeout();

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Starting startup timeout timer ({} minutes)",
                config.getStartupTimeoutMinutes());
        }

        startupTimeoutTask = server.getScheduler()
            .buildTask(plugin, this::executeStartupTimeout)
            .delay(config.getStartupTimeoutMinutes(), TimeUnit.MINUTES)
            .schedule();
    }

    /**
     * Cancel the startup timeout timer
     */
    public void cancelStartupTimeout() {
        if (startupTimeoutTask != null) {
            startupTimeoutTask.cancel();
            startupTimeoutTask = null;

            if (config.isLogTimerEvents()) {
                logger.info("[VelocityGCPController] Startup timeout timer cancelled");
            }
        }
    }

    /**
     * Execute startup timeout (orphaned startup)
     */
    private void executeStartupTimeout() {
        // Check if anyone has joined
        if (playerCount.get() > 0) {
            if (config.isLogTimerEvents()) {
                logger.info("[VelocityGCPController] Startup timeout cancelled - players joined");
            }
            return;
        }

        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Startup timeout expired (orphaned startup), executing GCP stop command");
        }

        gcpModule.stopInstance().thenAccept(success -> {
            if (success) {
                logger.info("[VelocityGCPController] Orphaned startup shutdown completed successfully");
            } else {
                logger.error("[VelocityGCPController] Orphaned startup shutdown failed");
            }
        });
    }

    /**
     * Get current player count
     */
    public int getPlayerCount() {
        return playerCount.get();
    }

    /**
     * Reset player count (useful for synchronization on plugin reload)
     */
    public void resetPlayerCount() {
        playerCount.set(0);
        if (config.isLogTimerEvents()) {
            logger.info("[VelocityGCPController] Player count reset to 0");
        }
    }
}
