package com.zenith.plugin.opencraft.intent;

import com.zenith.plugin.opencraft.auth.UserRole;

import java.util.List;
import java.util.Map;

/**
 * Immutable definition of one allowed admin command.
 * Constructed from com.zenith.plugin.opencraft.OpenCraftConfig.AllowedCommandConfig.
 * Only #commandId() and #description() are ever exposed to the LLM;
 * all other fields are Java-side only.
 */
public record CommandDefinition(
    /** Unique ID the LLM must return verbatim. */
    String        commandId,
    /** Human description injected into the admin system prompt. Must not reveal internals. */
    String        description,
    /** ZenithProxy command string — NEVER sent to the LLM. */
    String        zenithCommand,
    /** Minimum role required. */
    UserRole      roleRequired,
    /** "low", "medium", or "high". */
    String        riskLevel,
    /** If true, admin must explicitly confirm before execution. */
    boolean       confirmationRequired,
    /** Output fields to redact before logging or whispering. */
    List<String>  redactFields,
    /** Optional argument schema: paramName -> "string"|"integer"|"boolean". */
    Map<String, String> argumentSchema
) {
    public boolean isHighRisk() {
        return confirmationRequired || "high".equalsIgnoreCase(riskLevel);
    }
}
