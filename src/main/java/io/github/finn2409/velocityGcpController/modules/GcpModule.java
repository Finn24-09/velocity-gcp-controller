package io.github.finn2409.velocityGcpController.modules;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.compute.v1.*;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class GcpModule implements Module {
    private final PluginConfig config;
    private final Logger logger;
    private InstancesClient instancesClient;

    // Status cache
    private String cachedStatus;
    private Instant cacheExpiry;

    // Cooldown tracking
    private Instant startupCooldownExpiry;

    public GcpModule(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.cachedStatus = null;
        this.cacheExpiry = Instant.MIN;
        this.startupCooldownExpiry = Instant.MIN;
    }

    @Override
    public void initialize() throws Exception {
        if (!config.isGcpEnabled()) {
            return;
        }

        logger.info("[VelocityGCPController] Initializing GCP module...");

        try {
            InstancesSettings.Builder settingsBuilder = InstancesSettings.newBuilder();

            if (!config.isUseServiceAccount()) {
                // Use JSON key file
                GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                    new FileInputStream(config.getServiceAccountKey())
                );
                settingsBuilder.setCredentialsProvider(
                    FixedCredentialsProvider.create(credentials)
                );
                logger.info("[VelocityGCPController] Using service account key file for authentication");
            } else {
                // Use default credentials (instance service account)
                logger.info("[VelocityGCPController] Using default service account for authentication");
            }

            instancesClient = InstancesClient.create(settingsBuilder.build());
            logger.info("[VelocityGCPController] GCP module initialized successfully");

            // Test connection
            String status = getInstanceStatus();
            logger.info("[VelocityGCPController] Current instance status: {}", status);

        } catch (IOException e) {
            throw new Exception("Failed to initialize GCP client", e);
        }
    }

    @Override
    public void shutdown() {
        if (instancesClient != null) {
            instancesClient.close();
            logger.info("[VelocityGCPController] GCP module shut down");
        }
    }

    @Override
    public String getName() {
        return "GCP";
    }

    @Override
    public boolean isEnabled() {
        return config.isGcpEnabled();
    }

    /**
     * Get the current instance status
     * Uses caching to reduce API calls
     */
    public String getInstanceStatus() {
        if (Instant.now().isBefore(cacheExpiry) && cachedStatus != null) {
            return cachedStatus;
        }

        try {
            GetInstanceRequest request = GetInstanceRequest.newBuilder()
                .setProject(config.getProjectId())
                .setZone(config.getZone())
                .setInstance(config.getInstanceName())
                .build();

            Instance instance = instancesClient.get(request);
            cachedStatus = instance.getStatus();
            cacheExpiry = Instant.now().plusSeconds(config.getStatusCacheSeconds());

            return cachedStatus;
        } catch (Exception e) {
            logger.error("[VelocityGCPController] Failed to get instance status", e);
            return "UNKNOWN";
        }
    }

    /**
     * Check if instance is running and ready
     */
    public boolean isInstanceRunning() {
        String status = getInstanceStatus();
        return "RUNNING".equals(status);
    }

    /**
     * Start the GCP instance
     * Returns true if command was sent, false if cooldown is active
     */
    public CompletableFuture<Boolean> startInstance() {
        return CompletableFuture.supplyAsync(() -> {
            // Check cooldown
            if (Instant.now().isBefore(startupCooldownExpiry)) {
                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] Startup cooldown active, skipping GCP start command");
                }
                return false;
            }

            try {
                StartInstanceRequest request = StartInstanceRequest.newBuilder()
                    .setProject(config.getProjectId())
                    .setZone(config.getZone())
                    .setInstance(config.getInstanceName())
                    .build();

                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] Executing GCP start command for instance: {}", config.getInstanceName());
                }

                instancesClient.startAsync(request).get(90, TimeUnit.SECONDS);

                // Set cooldown
                startupCooldownExpiry = Instant.now().plusSeconds(config.getStartupCooldownMinutes() * 60L);

                // Invalidate cache
                cachedStatus = null;
                cacheExpiry = Instant.MIN;

                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] GCP start command completed successfully");
                }

                return true;
            } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
                logger.error("[VelocityGCPController] Failed to start instance", e);
                return false;
            }
        });
    }

    /**
     * Stop the GCP instance
     */
    public CompletableFuture<Boolean> stopInstance() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StopInstanceRequest request = StopInstanceRequest.newBuilder()
                    .setProject(config.getProjectId())
                    .setZone(config.getZone())
                    .setInstance(config.getInstanceName())
                    .build();

                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] Executing GCP stop command for instance: {}", config.getInstanceName());
                }

                instancesClient.stopAsync(request).get(90, TimeUnit.SECONDS);

                // Invalidate cache
                cachedStatus = null;
                cacheExpiry = Instant.MIN;

                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] GCP stop command completed successfully");
                }

                return true;
            } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
                logger.error("[VelocityGCPController] Failed to stop instance", e);
                return false;
            }
        });
    }

    /**
     * Check if the backend Minecraft server is reachable
     */
    public boolean isServerReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(config.getBackendAddress(), config.getBackendPort()),
                2000 // 2 second timeout
            );
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if both instance is running AND server is reachable
     */
    public boolean isServerAvailable() {
        return isInstanceRunning() && isServerReachable();
    }
}
