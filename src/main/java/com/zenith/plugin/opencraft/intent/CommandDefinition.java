package com.zenith.plugin.opencraft.intent;

import com.zenith.plugin.opencraft.auth.UserRole;

import java.util.List;
import java.util.Map;

public record CommandDefinition(
        String        commandId,
        String        description,
        String        zenithCommand,
        UserRole      roleRequired,
        String        riskLevel,
        boolean       confirmationRequired,
        List<String>  redactFields,
        Map<String, String> argumentSchema
) {
    public boolean isHighRisk() {
        return confirmationRequired || "high".equalsIgnoreCase(riskLevel);
    }
}
