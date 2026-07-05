package com.chatscope.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

	private static final String AUTH_COOKIE = "chatscope_auth";

	private final ClientRegistry registry;
	private final ChatDatabase database;
	// Live password from the mod config; empty/blank means the dashboard is open.
	private final Supplier<String> password;

	public DashboardServer(int port, ClientRegistry registry, ChatDatabase database, Supplier<String> password) {
		// Bound to all interfaces (0.0.0.0) so the dashboard is reachable from
		// other devices on the network and can be port-forwarded directly. Set a
		// password in the mod settings if you expose it beyond your own machine.
		super("0.0.0.0", port);
		this.registry = registry;
		this.database = database;
		this.password = password;
	}

	/**
	 * Optional password gate in front of everything (pages, API, WebSocket).
	 * Skipped entirely when no password is configured.
	 */
	@Override
	public Response serve(IHTTPSession session) {
		String pw = password.get();
		if (pw != null && !pw.isBlank() && !isAuthenticated(session, pw)) {
			return loginGate(session, pw);
		}
		return super.serve(session);
	}

	// Opaque cookie value derived from the password, so changing it logs everyone out.
	private static String tokenFor(String pw) {
		return "cs" + Integer.toHexString(("chatscope:" + pw).hashCode());
	}

	private boolean isAuthenticated(IHTTPSession session, String pw) {
		return tokenFor(pw).equals(session.getCookies().read(AUTH_COOKIE));
	}

	private Response loginGate(IHTTPSession session, String pw) {
		boolean failed = false;
		if (session.getMethod() == Method.POST) {
			try {
				session.parseBody(new HashMap<>());
			} catch (Exception ignored) {
				// Empty/malformed body just falls through to a failed login.
			}
			String submitted = session.getParameters().getOrDefault("password", List.of("")).get(0);
			if (pw.equals(submitted)) {
				Response redirect = NanoHTTPD.newFixedLengthResponse(
						Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
				redirect.addHeader("Location", "/");
				redirect.addHeader("Set-Cookie", AUTH_COOKIE + "=" + tokenFor(pw)
						+ "; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax");
				return redirect;
			}
			failed = true;
		}
		Response page = NanoHTTPD.newFixedLengthResponse(
				Response.Status.OK, "text/html; charset=utf-8", loginHtml(failed));
		page.addHeader("Cache-Control", "no-store");
		return page;
	}

	private static String loginHtml(boolean failed) {
		String error = failed ? "<p class=\"err\">Incorrect password.</p>" : "";
		// Plain concatenation, not String.formatted(): the CSS "width:100%"
		// would otherwise be read as a format specifier.
		return """
				<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>ChatScope — Login</title>
				<style>
				  html,body{height:100%;margin:0}
				  body{display:flex;align-items:center;justify-content:center;
				    background:#0d1117;color:#e6edf3;font-family:"Segoe UI",system-ui,sans-serif}
				  form{background:#161b22;border:1px solid #2d333b;border-radius:14px;
				    padding:28px 26px;width:min(320px,90vw);text-align:center;
				    box-shadow:0 12px 48px rgba(0,0,0,.4)}
				  h1{font-size:18px;margin:0 0 4px}
				  p.sub{color:#8b949e;font-size:13px;margin:0 0 18px}
				  input{width:100%;box-sizing:border-box;background:#0b0f14;
				    border:1px solid #2d333b;border-radius:8px;color:#e6edf3;
				    padding:10px 12px;font-size:14px;outline:none}
				  input:focus{border-color:#58a6ff}
				  button{width:100%;margin-top:12px;background:#238636;color:#fff;
				    border:none;border-radius:8px;padding:10px;font-size:14px;
				    font-weight:600;cursor:pointer}
				  button:hover{background:#2ea043}
				  p.err{color:#f85149;font-size:13px;margin:12px 0 0}
				</style></head><body>
				<form method="POST" action="/login">
				  <h1>🔭 ChatScope</h1>
				  <p class="sub">Enter the password to continue.</p>
				  <input type="password" name="password" placeholder="Password"
				    autofocus autocomplete="current-password">
				  <button type="submit">Enter</button>
				  """ + error + """
				</form></body></html>""";
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
