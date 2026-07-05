package com.chatscope.websocket;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.chatscope.ChatScope;
import com.chatscope.server.DashboardState;

import fi.iki.elonen.NanoWSD;

/**
 * Tracks all connected browser sockets and fans broadcasts out to them.
 *
 * All socket writes happen on one dedicated daemon thread. That keeps the
 * Minecraft client thread free of network I/O, serializes frames (NanoWSD
 * sockets are not safe for concurrent writes) and guarantees that the initial
 * snapshot a client receives and the incremental updates that follow are
 * ordered consistently.
 */
public class ClientRegistry {

	private static final byte[] PING_PAYLOAD = new byte[0];

	private final DashboardState state;
	private final List<DashboardSocket> sockets = new CopyOnWriteArrayList<>();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "chatscope-Broadcast");
		thread.setDaemon(true);
		return thread;
	});

	public ClientRegistry(DashboardState state) {
		this.state = state;
		// Periodic protocol-level pings so dead browser connections get
		// detected and cleaned up instead of leaking.
		executor.scheduleAtFixedRate(this::pingAll, 15, 15, TimeUnit.SECONDS);
	}

	/**
	 * Registers a freshly opened socket and sends it the full state snapshot.
	 * Registration and snapshot happen inside the broadcast thread so no
	 * update can slip between "snapshot taken" and "socket registered".
	 */
	public void onOpen(DashboardSocket socket) {
		executor.execute(() -> {
			sockets.add(socket);
			try {
				socket.send(state.buildInitMessage());
			} catch (IOException e) {
				drop(socket);
			}
			// Tell everyone the viewer count changed (incl. this new client).
			broadcastViewerCount();
		});
	}

	public void onClose(DashboardSocket socket) {
		if (sockets.remove(socket)) {
			broadcastViewerCount();
		}
	}

	/** Broadcasts the number of currently connected browsers. */
	private void broadcastViewerCount() {
		broadcast("{\"type\":\"viewers\",\"data\":{\"count\":" + sockets.size() + "}}");
	}

	/** Queues a JSON payload for delivery to every connected browser. */
	public void broadcast(String json) {
		executor.execute(() -> {
			for (DashboardSocket socket : sockets) {
				try {
					socket.send(json);
				} catch (IOException e) {
					drop(socket);
				}
			}
		});
	}

	private void pingAll() {
		for (DashboardSocket socket : sockets) {
			try {
				socket.ping(PING_PAYLOAD);
			} catch (IOException e) {
				drop(socket);
			}
		}
	}

	private void drop(DashboardSocket socket) {
		boolean removed = sockets.remove(socket);
		try {
			socket.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "write failed", false);
		} catch (IOException ignored) {
			// Socket is already broken; nothing more to do.
		}
		if (removed) {
			broadcastViewerCount();
		}
	}

	public void shutdown() {
		executor.shutdownNow();
		for (DashboardSocket socket : sockets) {
			try {
				socket.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "client shutting down", false);
			} catch (IOException ignored) {
				// Best effort during shutdown.
			}
		}
		sockets.clear();
		ChatScope.LOGGER.info("Dashboard WebSocket clients disconnected");
	}
}
