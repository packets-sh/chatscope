package com.chatscope.model;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A run of text that shares a single Minecraft style. A chat message is
 * flattened into a list of these so the browser can rebuild the formatting
 * with plain CSS.
 *
 * @param color hex color like {@code #FFAA00}, or {@code null} for the default chat color
 * @param hover tooltip shown when hovering this run (from Minecraft hover
 *              events: item tooltips, entity info, plain text), as a list of
 *              lines, each line a list of styled segments; {@code null} when
 *              the run has no hover event
 */
public record TextSegment(
		String text,
		String color,
		boolean bold,
		boolean italic,
		boolean underline,
		boolean strike,
		boolean obfuscated,
		List<List<TextSegment>> hover
) {

	public TextSegment(String text, String color, boolean bold, boolean italic,
			boolean underline, boolean strike, boolean obfuscated) {
		this(text, color, bold, italic, underline, strike, obfuscated, null);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("text", text);
		if (color != null) json.addProperty("color", color);
		// Only emit style flags that are set, to keep the payload small.
		if (bold) json.addProperty("bold", true);
		if (italic) json.addProperty("italic", true);
		if (underline) json.addProperty("underline", true);
		if (strike) json.addProperty("strike", true);
		if (obfuscated) json.addProperty("obfuscated", true);
		if (hover != null && !hover.isEmpty()) {
			JsonArray lines = new JsonArray();
			for (List<TextSegment> line : hover) {
				JsonArray lineJson = new JsonArray();
				for (TextSegment segment : line) {
					lineJson.add(segment.toJson());
				}
				lines.add(lineJson);
			}
			json.add("hover", lines);
		}
		return json;
	}
}
