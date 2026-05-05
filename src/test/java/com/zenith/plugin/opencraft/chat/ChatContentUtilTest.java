package com.zenith.plugin.opencraft.chat;

import com.zenith.mc.language.TranslationRegistryInitializer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatContentUtilTest {

    @BeforeAll
    static void registerTranslations() {
        TranslationRegistryInitializer.registerAllTranslations();
    }

    @Test
    void extractPlainText_flattensAdventureComponentToVisibleText() {
        final Component whisper = Component.translatable(
            "commands.message.display.incoming",
            Component.text("Notch"),
            Component.text("!oc hello")
        );

        assertEquals("Notch whispers to you: !oc hello", ChatContentUtil.extractPlainText(whisper));
    }

    @Test
    void stripColorCodes_removesFormattingFromChatMessage() {
        assertEquals("!oc hello", ChatContentUtil.stripColorCodes("§a!oc §bhello"));
    }
}
