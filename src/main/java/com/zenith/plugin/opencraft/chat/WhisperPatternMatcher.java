package com.zenith.plugin.opencraft.chat;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class WhisperPatternMatcher {

    private static final List<Pattern> FALLBACK_PATTERNS = List.of(
        Pattern.compile("^(\\S+) whispers to you: (.+)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^from (\\S+): (.+)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(\\S+) tells you: (.+)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(\\S+) whispers: (.+)$", Pattern.CASE_INSENSITIVE)
    );

    @Nullable
    WhisperMatch match(final String plainText, final String configuredPattern) {
        final Pattern customPattern = compileConfiguredPattern(configuredPattern);
        final WhisperMatch customMatch = matchPattern(customPattern, plainText);
        if (customMatch != null) {
            return customMatch;
        }
        for (final Pattern pattern : FALLBACK_PATTERNS) {
            final WhisperMatch fallbackMatch = matchPattern(pattern, plainText);
            if (fallbackMatch != null) {
                return fallbackMatch;
            }
        }
        return null;
    }

    private static Pattern compileConfiguredPattern(final String patternStr) {
        try {
            return Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        } catch (final PatternSyntaxException e) {
            return FALLBACK_PATTERNS.getFirst();
        }
    }

    @Nullable
    private static WhisperMatch matchPattern(final Pattern pattern, final String plainText) {
        final Matcher matcher = pattern.matcher(plainText);
        if (!matcher.matches() || matcher.groupCount() < 2) {
            return null;
        }
        final String sender = matcher.group(1);
        final String message = matcher.group(2);
        if (sender == null || sender.isBlank() || message == null) {
            return null;
        }
        return new WhisperMatch(sender, message);
    }

    record WhisperMatch(String senderName, String rawMessage) {
    }
}
