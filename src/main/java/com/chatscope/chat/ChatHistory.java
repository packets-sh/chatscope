package com.chatscope.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.chatscope.model.ChatMessage;

/**
 * Bounded in-memory ring buffer of the most recent chat messages. Written
 * from the Minecraft client thread, read from web server threads, hence the
 * coarse synchronization (contention is negligible at chat rates).
 */
public class ChatHistory {

	private final int maxSize;
	private final Deque<ChatMessage> messages = new ArrayDeque<>();

	public ChatHistory(int maxSize) {
		this.maxSize = maxSize;
	}

	public synchronized void add(ChatMessage message) {
		messages.addLast(message);
		while (messages.size() > maxSize) {
			messages.removeFirst();
		}
	}

	/** Immutable snapshot in chronological order. */
	public synchronized List<ChatMessage> snapshot() {
		return List.copyOf(messages);
	}
}
