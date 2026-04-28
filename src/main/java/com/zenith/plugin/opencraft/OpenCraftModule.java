package com.zenith.plugin.opencraft;

import com.github.rfresh2.EventConsumer;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandler;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.plugin.opencraft.chat.ChatHandler;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;

import java.util.Collections;
import java.util.List;

/**
 * Subscribe to inbound Minecraft chat packets and forward them to ChatHandler.
 */
public class OpenCraftModule extends Module {

    private final OpenCraftConfig config;
    private final ChatHandler     chatHandler;
    private final ComponentLogger logger;

    public OpenCraftModule(final OpenCraftConfig config,
                           final ChatHandler chatHandler,
                           final ComponentLogger logger) {
        this.config      = config;
        this.chatHandler = chatHandler;
        this.logger      = logger;
    }

    /** Keep this module enabled by default; operators can toggle it with /llm. */
    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return Collections.emptyList();
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("opencraft")
            .setPriority(1000) // run after standard handlers — read-only observation
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundPlayerChatPacket.class, new PlayerChatTap(chatHandler))
                .inbound(ClientboundSystemChatPacket.class, new SystemChatTap(chatHandler))
                .build())
            .build();
    }

    // ── Inbound observers ─────────────────────────────────────────────────────
    // Handlers return the packet unchanged (read-only tap).

    private record PlayerChatTap(ChatHandler chatHandler)
        implements PacketHandler<ClientboundPlayerChatPacket, ClientSession> {
        @Override
        public ClientboundPlayerChatPacket apply(final ClientboundPlayerChatPacket packet,
                                                 final ClientSession session) {
            try {
                chatHandler.onPlayerChat(packet.getSender(), packet.getContent());
            } catch (final Exception ignored) {
                // never block the network thread on plugin errors
            }
            return packet;
        }
    }

    private record SystemChatTap(ChatHandler chatHandler)
        implements PacketHandler<ClientboundSystemChatPacket, ClientSession> {
        @Override
        public ClientboundSystemChatPacket apply(final ClientboundSystemChatPacket packet,
                                                 final ClientSession session) {
            try {
                chatHandler.onSystemChat(packet.getContent());
            } catch (final Exception ignored) {
                // never block the network thread on plugin errors
            }
            return packet;
        }
    }
}
