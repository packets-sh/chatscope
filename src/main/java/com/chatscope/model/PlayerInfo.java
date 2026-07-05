package com.chatscope.model;

import com.google.gson.JsonObject;

/**
 * One entry of the tab list as shown on the dashboard. Record equality is
 * used to detect whether the player list changed between two polls.
 */
public record PlayerInfo(String name, String uuid, int ping, String gameMode) {

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.addProperty("uuid", uuid);
		json.addProperty("ping", ping);
		if (gameMode != null) json.addProperty("gameMode", gameMode);
		return json;
	}
}
