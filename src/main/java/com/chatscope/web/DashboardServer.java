package com.chatscope.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.chatscope.ChatScope;
import com.chatscope.db.ChatDatabase;
import com.chatscope.util.JsonUtil;
import com.chatscope.websocket.ClientRegistry;
import com.chatscope.websocket.DashboardSocket;
import com.google.gson.JsonObject;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * Embedded HTTP + WebSocket server, bound to all interfaces. Regular requests
 * are served from static assets bundled in the mod jar under {@code /web};
 * WebSocket upgrade requests (any path, the frontend uses {@code /ws}) become
 * {@link DashboardSocket}s.
 */
public class DashboardServer extends NanoWSD {

	private static final Map<String, String> MIME_TYPES = Map.of(
			"html", "text/html; charset=utf-8",
			"css", "text/css; charset=utf-8",
			"js", "application/javascript; charset=utf-8",
			"json", "application/json; charset=utf-8",
			"png", "image/png",
			"svg", "image/svg+xml",
			"ico", "image/x-icon"
	);

	private final ClientRegistry registry;
	private final ChatDatabase database;

	public DashboardServer(int port, ClientRegistry registry, ChatDatabase database) {
		// Bound to localhost. To reach the dashboard from another device, put a
		// tunnel/reverse proxy (e.g. cloudflared) in front of this port.
		super("127.0.0.1", port);
		this.registry = registry;
		this.database = database;
	}

	@Override
	protected WebSocket openWebSocket(IHTTPSession handshake) {
		return new DashboardSocket(handshake, registry);
	}

	@Override
	protected Response serveHttp(IHTTPSession session) {
		String uri = session.getUri();
		if (uri.equals("/api/player")) {
			return servePlayerHistory(session);
		}
		if (uri.equals("/api/players")) {
			return serveKnownPlayers();
		}
		if (uri.equals("/") || uri.isEmpty()) {
			uri = "/index.html";
		}
		// Refuse anything that could escape the bundled asset folder.
		if (uri.contains("..") || uri.contains("//")) {
			return notFound();
		}

		try (InputStream in = DashboardServer.class.getResourceAsStream("/web" + uri)) {
			if (in == null) {
				return notFound();
			}
			byte[] bytes = in.readAllBytes();
			Response response = NanoHTTPD.newFixedLengthResponse(
					Response.Status.OK,
					mimeFor(uri),
					new ByteArrayInputStream(bytes),
					bytes.length
			);
			// no-store keeps browsers and the cloudflared/Cloudflare edge from
			// serving a stale bundle; versioned ?v= URLs are the backup buster.
			response.addHeader("Cache-Control", "no-store, must-revalidate");
			return response;
		} catch (IOException e) {
			return NanoHTTPD.newFixedLengthResponse(
					Response.Status.INTERNAL_ERROR, "text/plain", "Internal error");
		}
	}

	/**
	 * {@code GET /api/player?name=<username>[&limit=<n>]} — the player's
	 * logged message history from the local database, newest first.
	 */
	private Response servePlayerHistory(IHTTPSession session) {
		if (database == null) {
			return jsonError(Response.Status.SERVICE_UNAVAILABLE, "chat database unavailable");
		}
		List<String> names = session.getParameters().get("name");
		if (names == null || names.isEmpty() || names.get(0).isBlank()) {
			return jsonError(Response.Status.BAD_REQUEST, "missing 'name' parameter");
		}
		String name = names.get(0);

		int limit = clampInt(session, "limit", 500, 1, 500);
		int offset = clampInt(session, "offset", 0, 0, Integer.MAX_VALUE);
		List<String> qs = session.getParameters().get("q");
		String query = qs != null && !qs.isEmpty() ? qs.get(0) : null;

		try {
			JsonObject result = database.queryByPlayer(name, limit, offset, query);
			result.addProperty("name", name);
			result.addProperty("offset", offset);
			result.addProperty("limit", limit);
			Response response = NanoHTTPD.newFixedLengthResponse(
					Response.Status.OK, "application/json; charset=utf-8", JsonUtil.GSON.toJson(result));
			response.addHeader("Cache-Control", "no-store");
			return response;
		} catch (SQLException e) {
			ChatScope.LOGGER.warn("Player history query failed", e);
			return jsonError(Response.Status.INTERNAL_ERROR, "query failed");
		}
	}

	private static int clampInt(IHTTPSession session, String param, int def, int min, int max) {
		List<String> values = session.getParameters().get(param);
		if (values == null || values.isEmpty()) {
			return def;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(values.get(0))));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** {@code GET /api/players} — every player known from the chat log. */
	private Response serveKnownPlayers() {
		if (database == null) {
			return jsonError(Response.Status.SERVICE_UNAVAILABLE, "chat database unavailable");
		}
		try {
			JsonObject result = new JsonObject();
			result.add("players", database.knownPlayers());
			Response response = NanoHTTPD.newFixedLengthResponse(
					Response.Status.OK, "application/json; charset=utf-8", JsonUtil.GSON.toJson(result));
			response.addHeader("Cache-Control", "no-store");
			return response;
		} catch (SQLException e) {
			ChatScope.LOGGER.warn("Known-players query failed", e);
			return jsonError(Response.Status.INTERNAL_ERROR, "query failed");
		}
	}

	private static Response jsonError(Response.Status status, String message) {
		JsonObject json = new JsonObject();
		json.addProperty("error", message);
		return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", JsonUtil.GSON.toJson(json));
	}

	private static Response notFound() {
		return NanoHTTPD.newFixedLengthResponse(
				Response.Status.NOT_FOUND, "text/plain", "Not found");
	}

	private static String mimeFor(String uri) {
		int dot = uri.lastIndexOf('.');
		String extension = dot >= 0 ? uri.substring(dot + 1).toLowerCase() : "";
		return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
	}
}
