package com.chatscope.players;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.chatscope.model.PlayerInfo;
import com.chatscope.server.DashboardState;
import com.chatscope.util.JsonUtil;
import com.chatscope.websocket.ClientRegistry;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

/**
 * Polls the tab list once per second (there is no Fabric event for tab list
 * changes, and polling 20x fewer times than the tick rate keeps the cost
 * negligible). A broadcast is only sent when something actually changed,
 * which record equality on {@link PlayerInfo} detects for free.
 */
public class PlayerListTracker {

	private static final int POLL_INTERVAL_TICKS = 20;

	private final DashboardState state;
	private final ClientRegistry registry;

	private int tickCounter;
	private List<PlayerInfo> lastPlayers = List.of();

	public PlayerListTracker(DashboardState state, ClientRegistry registry) {
		this.state = state;
		this.registry = registry;
	}

	public void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++tickCounter < POLL_INTERVAL_TICKS) {
				return;
			}
			tickCounter = 0;

			ClientPlayNetworkHandler handler = client.getNetworkHandler();
			List<PlayerInfo> current = handler == null ? List.of() : snapshot(handler);

			if (!current.equals(lastPlayers)) {
				lastPlayers = current;
				state.setPlayers(current);

				JsonObject data = new JsonObject();
				data.add("players", DashboardState.playersJson(current));
				registry.broadcast(JsonUtil.envelope("players", data));
			}
		});
	}

	private static List<PlayerInfo> snapshot(ClientPlayNetworkHandler handler) {
		return handler.getPlayerList().stream()
				.map(PlayerListTracker::toInfo)
				.sorted(Comparator.comparing(info -> info.name().toLowerCase(Locale.ROOT)))
				.toList();
	}

	private static PlayerInfo toInfo(PlayerListEntry entry) {
		String gameMode = entry.getGameMode() == null
				? null
				: entry.getGameMode().name().toLowerCase(Locale.ROOT);
		return new PlayerInfo(
				entry.getProfile().name(),
				entry.getProfile().id().toString(),
				entry.getLatency(),
				gameMode
		);
	}
}
