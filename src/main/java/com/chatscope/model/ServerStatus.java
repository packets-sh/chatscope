package com.chatscope.model;

import com.google.gson.JsonObject;

/**
 * Where the client is currently connected.
 *
 * @param kind           one of {@code server}, {@code singleplayer}, {@code realm},
 *                       {@code disconnected}
 * @param connectedSince epoch millis of the moment the connection was
 *                       established, or 0 when disconnected; the frontend uses
 *                       this to render a live uptime counter
 */
public record ServerStatus(
		boolean connected,
		String kind,
		String name,
		String address,
		String localPlayer,
		long connectedSince
) {

	public static ServerStatus disconnected() {
		return new ServerStatus(false, "disconnected", null, null, null, 0L);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("connected", connected);
		json.addProperty("kind", kind);
		if (name != null) json.addProperty("name", name);
		if (address != null) json.addProperty("address", address);
		if (localPlayer != null) json.addProperty("localPlayer", localPlayer);
		if (connectedSince > 0) json.addProperty("connectedSince", connectedSince);
		return json;
	}
}
