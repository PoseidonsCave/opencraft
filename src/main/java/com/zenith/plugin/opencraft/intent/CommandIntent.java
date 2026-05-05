package com.zenith.plugin.opencraft.intent;

import java.util.Map;
import java.util.UUID;

public record CommandIntent(
    String              commandId,
    Map<String, String> arguments,
    String              explanation
) {}
