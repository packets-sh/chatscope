package com.chatscope.model;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.chatscope.util.TextConverter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.text.Text;

/**
 * An immutable snapshot of one chat line, ready to be serialized to the
 * dashboard. Instances are created on the Minecraft client thread and then
 * handed to the web server thread, so immutability doubles as thread safety.
 *
 * @param id         monotonically increasing id, used by the frontend to de-duplicate
 * @param type       one of {@code chat}, {@code system}, {@code join}, {@code leave},
 *                   {@code death}, {@code advancement}
 * @param sender     username of the sending player, or {@code null} for system messages
 * @param senderUuid UUID of the sending player, or {@code null} for system messages
 */
public record ChatMessage(
		long id,
		long timestamp,
		String type,
		String sender,
		String senderUuid,
		String plain,
		List<TextSegment> segments
) {

	private static final AtomicLong NEXT_ID = new AtomicLong(1);

	public static ChatMessage of(String type, String sender, String senderUuid, Text text) {
		List<TextSegment> segments = TextConverter.toSegments(text);
		return new ChatMessage(
				NEXT_ID.getAndIncrement(),
				System.currentTimeMillis(),
				type,
				sender,
				senderUuid,
				// Derived from the segments, so legacy § codes are stripped.
				TextConverter.toPlain(segments),
				segments
		);
	}

	public JsonArray segmentsJson() {
		JsonArray segs = new JsonArray();
		for (TextSegment segment : segments) {
			segs.add(segment.toJson());
		}
		return segs;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("id", id);
		json.addProperty("ts", timestamp);
		json.addProperty("type", type);
		if (sender != null) json.addProperty("sender", sender);
		if (senderUuid != null) json.addProperty("senderUuid", senderUuid);
		json.addProperty("plain", plain);
		json.add("segments", segmentsJson());
		return json;
	}
}
