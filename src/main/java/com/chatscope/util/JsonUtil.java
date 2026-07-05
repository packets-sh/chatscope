package com.chatscope.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Small helpers for the JSON envelope format used on the WebSocket:
 * {@code {"type": "...", "data": {...}}}.
 */
public final class JsonUtil {

	public static final Gson GSON = new Gson();

	private JsonUtil() {
	}

	public static String envelope(String type, JsonElement data) {
		JsonObject json = new JsonObject();
		json.addProperty("type", type);
		json.add("data", data);
		return GSON.toJson(json);
	}
}
