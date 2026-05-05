package com.zenith.plugin.opencraft.chat;

import com.zenith.util.ComponentSerializer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

final class ChatContentUtil {

    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);

    private ChatContentUtil() {}

    static String stripColorCodes(final String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return COLOR_CODE.matcher(s).replaceAll("");
    }

    @Nullable
    static String extractPlainText(@Nullable final Object component) {
        if (component == null) {
            return null;
        }
        if (component instanceof Component adventureComponent) {
            return stripColorCodes(ComponentSerializer.serializePlain(adventureComponent));
        }
        return stripColorCodes(String.valueOf(component));
    }
}
