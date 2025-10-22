package io.github.finn2409.velocityGcpController.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class MessageFormatter {
    private static final LegacyComponentSerializer SERIALIZER =
        LegacyComponentSerializer.legacyAmpersand();

    /**
     * Converts legacy color codes (& prefix) to Adventure Components
     */
    public static Component format(String message) {
        return SERIALIZER.deserialize(message);
    }
}
