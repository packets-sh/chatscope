package com.chatscope.chat;

import java.util.List;

import com.chatscope.db.ChatDatabase;
import com.chatscope.model.ChatMessage;
import com.chatscope.model.ServerStatus;
import com.chatscope.model.TextSegment;
import com.chatscope.server.DashboardState;
import com.chatscope.util.JsonUtil;
import com.chatscope.util.TextConverter;
import com.chatscope.websocket.ClientRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;

/**
 * Hooks the Fabric client message events and forwards every incoming chat
 * line to the dashboard. Handlers run on the Minecraft client thread; the
 * only work done here is text flattening, the actual network I/O is deferred
 * to the broadcast thread by {@link ClientRegistry}.
 */
public class ChatListener {

	private final DashboardState state;
	private final ClientRegistry registry;
	private final ChatDatabase database;

	public ChatListener(DashboardState state, ClientRegistry registry, ChatDatabase database) {
		this.state = state;
		this.registry = registry;
		this.database = database;
	}

	public void register() {
		// Signed player chat ("<Steve> hello").
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String senderName = sender != null ? sender.name() : null;
			String senderUuid = sender != null ? sender.id().toString() : null;
			publish(ChatMessage.of("chat", senderName, senderUuid, message));
		});

		// Everything else the server prints into chat: system messages,
		// join/leave, deaths, advancements, command output, plugin messages.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				// Action bar text: shown live but not recorded, since some
				// servers rewrite it every tick.
				broadcastActionBar(message);
				return;
			}
			// Plugin chat has no signed sender; recover the real account name
			// from the message's hover/click metadata where possible, so the
			// database logs under the true username instead of a nickname.
			SenderResolver.Sender hint = SenderResolver.resolve(message);
			publish(ChatMessage.of(classify(message),
					hint != null ? hint.name() : null,
					hint != null ? hint.uuid() : null,
					message));
		});
	}

	private void publish(ChatMessage message) {
		state.history().add(message);
		if (database != null) {
			ServerStatus status = state.status();
			String server = !status.connected() ? null
					: status.address() != null ? status.address() : status.name();
			database.insertAsync(message, server);
		}
		registry.broadcast(JsonUtil.envelope("chat", message.toJson()));
	}

	private void broadcastActionBar(Text message) {
		List<TextSegment> converted = TextConverter.toSegments(message);
		JsonObject data = new JsonObject();
		data.addProperty("plain", TextConverter.toPlain(converted));
		JsonArray segments = new JsonArray();
		for (TextSegment segment : converted) {
			segments.add(segment.toJson());
		}
		data.add("segments", segments);
		registry.broadcast(JsonUtil.envelope("actionbar", data));
	}

	/**
	 * Best-effort classification of system messages by their translation key,
	 * so the frontend can tint join/leave/death/advancement lines.
	 */
	private static String classify(Text message) {
		TextContent content = message.getContent();
		if (content instanceof TranslatableTextContent translatable) {
			String key = translatable.getKey();
			if (key.startsWith("multiplayer.player.joined")) return "join";
			if (key.startsWith("multiplayer.player.left")) return "leave";
			if (key.startsWith("death.")) return "death";
			if (key.startsWith("chat.type.advancement")) return "advancement";
		}
		return "system";
	}
}
