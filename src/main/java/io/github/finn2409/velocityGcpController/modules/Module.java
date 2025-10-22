package io.github.finn2409.velocityGcpController.modules;

public interface Module {
    /**
     * Initialize the module
     * @throws Exception if initialization fails
     */
    void initialize() throws Exception;

    /**
     * Shutdown the module gracefully
     */
    void shutdown();

    /**
     * Get the module name
     */
    String getName();

    /**
     * Check if the module is enabled
     */
    boolean isEnabled();
}
