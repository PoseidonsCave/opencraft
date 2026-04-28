package com.zenith.plugin.opencraft.intent;

import java.util.Map;
import java.util.UUID;

/**
 * Structured command intent parsed from model output.
 * commandId must match an allowlisted command.
 * arguments are validated before execution.
 */
public record CommandIntent(
    String              commandId,
    Map<String, String> arguments,
    String              explanation
) {}
