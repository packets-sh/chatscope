package com.chatscope.server;

import java.util.List;

import com.chatscope.chat.ChatHistory;
import com.chatscope.model.ChatMessage;
import com.chatscope.model.PlayerInfo;
import com.chatscope.model.ServerStatus;
import com.chatscope.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The single source of truth shared between the Minecraft side (writers) and
 * the web side (readers). New browser clients are served a full snapshot of
 * this state; afterwards they only receive incremental updates.
 */
public class DashboardState {

	private final ChatHistory history;
	private volatile ServerStatus status = ServerStatus.disconnected();
	private volatile List<PlayerInfo> players = List.of();

	public DashboardState(ChatHistory history) {
		this.history = history;
	}

	public ChatHistory history() {
		return history;
	}

	public ServerStatus status() {
		return status;
	}

	public void setStatus(ServerStatus status) {
		this.status = status;
	}

	public List<PlayerInfo> players() {
		return players;
	}

	public void setPlayers(List<PlayerInfo> players) {
		this.players = players;
	}

	/** Full state snapshot sent to a browser right after it connects. */
	public String buildInitMessage() {
		JsonObject data = new JsonObject();
		data.add("server", status.toJson());
		data.add("players", playersJson(players));

		JsonArray historyJson = new JsonArray();
		for (ChatMessage message : history.snapshot()) {
			historyJson.add(message.toJson());
		}
		data.add("history", historyJson);

		return JsonUtil.envelope("init", data);
	}

	public static JsonArray playersJson(List<PlayerInfo> players) {
		JsonArray json = new JsonArray();
		for (PlayerInfo player : players) {
			json.add(player.toJson());
		}
		return json;
	}
}
