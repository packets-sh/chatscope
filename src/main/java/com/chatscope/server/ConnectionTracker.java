package com.chatscope.server;

import java.util.List;

import com.chatscope.db.ChatDatabase;
import com.chatscope.model.ServerStatus;
import com.chatscope.util.JsonUtil;
import com.chatscope.websocket.ClientRegistry;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Tracks joins and disconnects and keeps the dashboard's server panel in
 * sync. Distinguishes multiplayer servers, singleplayer worlds and Realms.
 */
public class ConnectionTracker {

	private final DashboardState state;
	private final ClientRegistry registry;
	private final ChatDatabase database;

	public ConnectionTracker(DashboardState state, ClientRegistry registry, ChatDatabase database) {
		this.state = state;
		this.registry = registry;
		this.database = database;
	}

	public void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ServerStatus status = buildStatus(client);
			// Point the chat log at this server's own table before its
			// messages start arriving.
			if (database != null) {
				database.useServer(status.address() != null ? status.address() : status.name());
			}
			update(status);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			state.setPlayers(List.of());
			update(ServerStatus.disconnected());

			// Also push an empty player list so the sidebar clears instantly
			// instead of waiting for the next tab list poll.
			JsonObject data = new JsonObject();
			data.add("players", DashboardState.playersJson(List.of()));
			registry.broadcast(JsonUtil.envelope("players", data));
		});
	}

	private void update(ServerStatus status) {
		state.setStatus(status);
		JsonObject data = new JsonObject();
		data.add("server", status.toJson());
		registry.broadcast(JsonUtil.envelope("server", data));
	}

	private static ServerStatus buildStatus(MinecraftClient client) {
		long now = System.currentTimeMillis();
		String localPlayer = client.getSession().getUsername();

		if (client.isInSingleplayer()) {
			return new ServerStatus(true, "singleplayer", "Singleplayer", null, localPlayer, now);
		}

		ServerInfo info = client.getCurrentServerEntry();
		if (info != null && info.isRealm()) {
			return new ServerStatus(true, "realm", "Realms", null, localPlayer, now);
		}
		String name = info != null && info.name != null && !info.name.isBlank() ? info.name : null;
		String address = info != null ? info.address : null;
		if (name == null) {
			name = address != null ? address : "Multiplayer server";
		}
		return new ServerStatus(true, "server", name, address, localPlayer, now);
	}
}
