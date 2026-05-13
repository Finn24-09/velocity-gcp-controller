package io.github.finn2409.velocityGcpController.modules;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.compute.v1.*;
import io.github.finn2409.velocityGcpController.config.PluginConfig;
import io.github.finn2409.velocityGcpController.config.ShutdownMode;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GcpModule implements Module {
    // GCP Compute Engine v1 instance status values.
    // See: https://cloud.google.com/compute/docs/instances/instance-life-cycle
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_TERMINATED = "TERMINATED";
    private static final String STATUS_SUSPENDED = "SUSPENDED";

    private static final long GCP_OPERATION_TIMEOUT_SECONDS = 90L;

    private final PluginConfig config;
    private final Logger logger;
    private InstancesClient instancesClient;

    // Status cache
    private String cachedStatus;
    private Instant cacheExpiry;

    // Cooldown tracking (covers both start and resume)
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
                GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                    new FileInputStream(config.getServiceAccountKey())
                );
                settingsBuilder.setCredentialsProvider(
                    FixedCredentialsProvider.create(credentials)
                );
                logger.info("[VelocityGCPController] Using service account key file for authentication");
            } else {
                logger.info("[VelocityGCPController] Using default service account for authentication");
            }

            instancesClient = InstancesClient.create(settingsBuilder.build());
            logger.info("[VelocityGCPController] GCP module initialized successfully");
            logger.info("[VelocityGCPController] Shutdown mode: {}", config.getShutdownMode());

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
        return STATUS_RUNNING.equals(getInstanceStatus());
    }

    /**
     * Bring the instance into a RUNNING state.
     *
     * <p>Dispatches based on the current GCP status: {@code TERMINATED} → {@code start},
     * {@code SUSPENDED} → {@code resume}, {@code RUNNING} → no-op. Transitional
     * statuses are no-ops because the VM is already heading somewhere. The
     * existing startup cooldown applies to both start and resume to suppress
     * duplicate commands when many players reconnect at once.</p>
     *
     * @return {@code true} if the instance is now running or a start/resume
     *     command was issued successfully; {@code false} on cooldown skip,
     *     transitional state, or API failure.
     */
    public CompletableFuture<Boolean> startInstance() {
        return CompletableFuture.supplyAsync(() -> {
            if (Instant.now().isBefore(startupCooldownExpiry)) {
                if (config.isLogGcpCommands()) {
                    logger.info("[VelocityGCPController] Startup cooldown active, skipping GCP start/resume command");
                }
                return false;
            }

            String status = getInstanceStatus();
            switch (status) {
                case STATUS_TERMINATED:
                    return executeStart();
                case STATUS_SUSPENDED:
                    return executeResume();
                case STATUS_RUNNING:
                    if (config.isLogGcpCommands()) {
                        logger.info("[VelocityGCPController] Instance already RUNNING, skipping start");
                    }
                    return true;
                default:
                    if (config.isLogGcpCommands()) {
                        logger.info("[VelocityGCPController] Cannot start: instance status is {} (transitional or unknown)", status);
                    }
                    return false;
            }
        });
    }

    /**
     * Take the instance out of RUNNING using the configured shutdown mode.
     *
     * <p>Mode {@code SUSPEND} saves the VM's memory to a snapshot disk for fast
     * subsequent resume (small ongoing storage cost). Mode {@code STOP} fully
     * terminates the VM (cheapest). If a suspend attempt fails (e.g., the
     * machine type does not support suspend, or the GCP API rejects the
     * request), the plugin logs a warning and falls back to a full stop so the
     * VM never ends up orphaned in RUNNING state.</p>
     *
     * @return {@code true} if the instance is shut down (or already in the
     *     target state); {@code false} on transitional state or API failure
     *     that fallback could not recover from.
     */
    public CompletableFuture<Boolean> shutdownInstance() {
        return CompletableFuture.supplyAsync(() -> {
            String status = getInstanceStatus();
            ShutdownMode mode = config.getShutdownMode();

            switch (status) {
                case STATUS_RUNNING:
                    if (mode == ShutdownMode.SUSPEND) {
                        if (executeSuspend()) {
                            return true;
                        }
                        logger.warn("[VelocityGCPController] Suspend failed, falling back to stop instance: {}",
                            config.getInstanceName());
                        return executeStop();
                    }
                    return executeStop();
                case STATUS_SUSPENDED:
                    if (mode == ShutdownMode.SUSPEND) {
                        if (config.isLogGcpCommands()) {
                            logger.info("[VelocityGCPController] Instance already SUSPENDED, no shutdown needed");
                        }
                        return true;
                    }
                    // Mode is STOP but VM is suspended — promote to full stop.
                    return executeStop();
                case STATUS_TERMINATED:
                    if (config.isLogGcpCommands()) {
                        logger.info("[VelocityGCPController] Instance already TERMINATED, no shutdown needed");
                    }
                    return true;
                default:
                    if (config.isLogGcpCommands()) {
                        logger.info("[VelocityGCPController] Cannot shutdown: instance status is {} (transitional or unknown)", status);
                    }
                    return false;
            }
        });
    }

    /**
     * @deprecated use {@link #shutdownInstance()} which honors {@code shutdown-mode}.
     * Kept only as a transitional alias and may be removed in a future release.
     */
    @Deprecated
    public CompletableFuture<Boolean> stopInstance() {
        return shutdownInstance();
    }

    private boolean executeStart() {
        try {
            StartInstanceRequest request = StartInstanceRequest.newBuilder()
                .setProject(config.getProjectId())
                .setZone(config.getZone())
                .setInstance(config.getInstanceName())
                .build();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] Executing GCP start command for instance: {}", config.getInstanceName());
            }

            instancesClient.startAsync(request).get(GCP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            startupCooldownExpiry = Instant.now().plusSeconds(config.getStartupCooldownMinutes() * 60L);
            invalidateStatusCache();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] GCP start command completed successfully");
            }
            return true;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.error("[VelocityGCPController] Failed to start instance", e);
            return false;
        }
    }

    private boolean executeResume() {
        try {
            ResumeInstanceRequest request = ResumeInstanceRequest.newBuilder()
                .setProject(config.getProjectId())
                .setZone(config.getZone())
                .setInstance(config.getInstanceName())
                .build();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] Instance is SUSPENDED, executing GCP resume command for instance: {}",
                    config.getInstanceName());
            }

            instancesClient.resumeAsync(request).get(GCP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            startupCooldownExpiry = Instant.now().plusSeconds(config.getStartupCooldownMinutes() * 60L);
            invalidateStatusCache();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] GCP resume command completed successfully");
            }
            return true;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.error("[VelocityGCPController] Failed to resume instance", e);
            return false;
        }
    }

    private boolean executeStop() {
        try {
            StopInstanceRequest request = StopInstanceRequest.newBuilder()
                .setProject(config.getProjectId())
                .setZone(config.getZone())
                .setInstance(config.getInstanceName())
                .build();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] Executing GCP stop command for instance: {}", config.getInstanceName());
            }

            instancesClient.stopAsync(request).get(GCP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            invalidateStatusCache();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] GCP stop command completed successfully");
            }
            return true;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.error("[VelocityGCPController] Failed to stop instance", e);
            return false;
        }
    }

    /**
     * Suspend the instance. Returns false on any failure so callers can fall
     * back to a full stop. Common failure modes: machine type does not
     * support suspend (e.g., some shared-core E2, GPU, or local-SSD configs),
     * or the 60-day suspended-VM limit was previously exceeded.
     */
    private boolean executeSuspend() {
        try {
            SuspendInstanceRequest request = SuspendInstanceRequest.newBuilder()
                .setProject(config.getProjectId())
                .setZone(config.getZone())
                .setInstance(config.getInstanceName())
                .build();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] Executing GCP suspend command for instance: {}", config.getInstanceName());
            }

            instancesClient.suspendAsync(request).get(GCP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            invalidateStatusCache();

            if (config.isLogGcpCommands()) {
                logger.info("[VelocityGCPController] GCP suspend command completed successfully");
            }
            return true;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.error("[VelocityGCPController] Failed to suspend instance", e);
            return false;
        }
    }

    private void invalidateStatusCache() {
        cachedStatus = null;
        cacheExpiry = Instant.MIN;
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
