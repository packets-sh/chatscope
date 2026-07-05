package com.chatscope;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chatscope.chat.ChatHistory;
import com.chatscope.chat.ChatListener;
import com.chatscope.config.ChatScopeConfig;
import com.chatscope.db.ChatDatabase;
import com.chatscope.players.PlayerListTracker;
import com.chatscope.server.ConnectionTracker;
import com.chatscope.server.DashboardState;
import com.chatscope.web.DashboardServer;
import com.chatscope.websocket.ClientRegistry;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point. Wires the Minecraft event listeners to the shared dashboard
 * state and starts the embedded web server on port {@code 25534}, listening
 * on all network interfaces.
 */
public class ChatScope implements ClientModInitializer {

	public static final String MOD_ID = "chatscope";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int PORT = 25534;
	public static final int CHAT_HISTORY_SIZE = 1000;

	private DashboardServer server;
	private ClientRegistry registry;
	private ChatDatabase database;

	@Override
	public void onInitializeClient() {
		// Mod settings (editable via Mod Menu, persisted to config/chatscope.json).
		AutoConfig.register(ChatScopeConfig.class, GsonConfigSerializer::new);

		DashboardState state = new DashboardState(new ChatHistory(CHAT_HISTORY_SIZE));
		registry = new ClientRegistry(state);

		// Persistent chat log; the dashboard degrades gracefully without it.
		try {
			Path dbFile = FabricLoader.getInstance().getGameDir().resolve("chatscope").resolve("chat.db");
			database = new ChatDatabase(dbFile);
			LOGGER.info("Chat log database: {}", dbFile);
		} catch (Exception e) {
			LOGGER.error("Failed to open chat log database; per-player history will be unavailable", e);
		}

		new ChatListener(state, registry, database).register();
		new PlayerListTracker(state, registry).register();
		new ConnectionTracker(state, registry, database).register();

		// Password is read live from the config each request, so changing it in
		// the settings screen takes effect without a restart.
		server = new DashboardServer(PORT, registry, database,
				() -> AutoConfig.getConfigHolder(ChatScopeConfig.class).getConfig().password);
		try {
			// timeout 0 = sockets never time out on their own; WebSockets stay
			// open and dead ones are reaped by the registry's ping schedule.
			server.start(0, true);
			LOGGER.info("ChatScope running on http://localhost:{}", PORT);
		} catch (IOException e) {
			LOGGER.error("Failed to start ChatScope on port {} (already in use?)", PORT, e);
		}

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
	}

	private void shutdown() {
		LOGGER.info("Shutting down ChatScope");
		if (registry != null) {
			registry.shutdown();
		}
		if (server != null) {
			server.stop();
		}
		if (database != null) {
			database.close();
		}
	}
}
