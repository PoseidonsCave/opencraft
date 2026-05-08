package com.zenith.plugin.opencraft.intent;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandIntentNormalizer {

    private static final Set<String> MOVEMENT_ALIASES = Set.of(
        "walk", "move", "go", "travel", "navigate", "path"
    );
    private static final Pattern BLOCKS_PATTERN = Pattern.compile("(\\d+)\\s*blocks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTION_PATTERN = Pattern.compile(
        "(north|south|east|west|[+-]x|[+-]z|negx|negz|posx|posz|negativex|negativez|positivex|positivez)",
        Pattern.CASE_INSENSITIVE
    );

    private CommandIntentNormalizer() {}

    public static CommandIntent normalize(final CommandIntent intent,
                                          @Nullable final String fallbackText) {
        if (intent == null || intent.commandId() == null) {
            return intent;
        }
        final String commandId = intent.commandId().strip().toLowerCase(Locale.ROOT);
        if (commandId.isBlank()) return intent;

        if (MOVEMENT_ALIASES.contains(commandId)) {
            final CommandIntent normalizedMovement = normalizeMovement(intent, fallbackText);
            if (normalizedMovement != null) return normalizedMovement;
        }

        return normalizeExistingCommandArguments(intent);
    }

    private static @Nullable CommandIntent normalizeMovement(final CommandIntent intent,
                                                             @Nullable final String fallbackText) {
        final Map<String, String> args = new HashMap<>(intent.arguments());
        final String text = ((fallbackText == null ? "" : fallbackText) + " " + intent.explanation()).strip();

        final String direction = extractFirst(args, "direction", "cardinal", "axis");
        final String blocks = extractFirst(args, "blocks", "distance", "steps", "count");
        if (direction != null && blocks != null) {
            return new CommandIntent(
                "pathfinder.cardinal",
                Map.of("direction", direction, "blocks", blocks),
                intent.explanation()
            );
        }

        if (hasKeys(args, "x", "y", "z")) {
            return new CommandIntent(
                "pathfinder.goto.xyz",
                Map.of("x", args.get("x"), "y", args.get("y"), "z", args.get("z")),
                intent.explanation()
            );
        }
        if (hasKeys(args, "x", "z")) {
            return new CommandIntent(
                "pathfinder.goto.xz",
                Map.of("x", args.get("x"), "z", args.get("z")),
                intent.explanation()
            );
        }

        final String textDirection = findDirection(text);
        final String textBlocks = findBlocks(text);
        if (textDirection != null && textBlocks != null) {
            return new CommandIntent(
                "pathfinder.cardinal",
                Map.of("direction", textDirection, "blocks", textBlocks),
                intent.explanation()
            );
        }
        if (textBlocks != null) {
            return new CommandIntent(
                "pathfinder.thisway",
                Map.of("blocks", textBlocks),
                intent.explanation()
            );
        }

        return null;
    }

    private static CommandIntent normalizeExistingCommandArguments(final CommandIntent intent) {
        if (!"pathfinder.cardinal".equals(intent.commandId())) {
            return intent;
        }
        final String direction = extractFirst(intent.arguments(), "direction", "cardinal", "axis");
        final String blocks = extractFirst(intent.arguments(), "blocks", "distance", "steps", "count");
        if (direction == null || blocks == null) {
            return intent;
        }
        return new CommandIntent(
            "pathfinder.cardinal",
            Map.of("direction", direction, "blocks", blocks),
            intent.explanation()
        );
    }

    private static boolean hasKeys(final Map<String, String> args, final String... keys) {
        for (final String key : keys) {
            final String value = args.get(key);
            if (value == null || value.isBlank()) return false;
        }
        return true;
    }

    private static @Nullable String extractFirst(final Map<String, String> args,
                                                 final String... keys) {
        for (final String key : keys) {
            final String value = args.get(key);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static @Nullable String findBlocks(final String text) {
        if (text == null || text.isBlank()) return null;
        final Matcher matcher = BLOCKS_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static @Nullable String findDirection(final String text) {
        if (text == null || text.isBlank()) return null;
        final Matcher matcher = DIRECTION_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
