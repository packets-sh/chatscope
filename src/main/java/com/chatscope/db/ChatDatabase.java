package com.chatscope.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.chatscope.ChatScope;
import com.chatscope.model.ChatMessage;
import com.chatscope.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persistent chat log backed by a local SQLite file. Every chat line ever
 * received is inserted (writes happen on a dedicated daemon thread so the
 * Minecraft client thread never touches the disk); the dashboard queries it
 * for per-player message history.
 *
 * Each server the client connects to gets its OWN table
 * ({@code messages_<sanitized address>}), so history and player data never mix
 * between servers. The active table follows the connected server via
 * {@link #useServer(String)}. The single database file can still be opened
 * with any external SQLite browser.
 */
public class ChatDatabase implements AutoCloseable {

	private final Connection connection;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "chatscope-DB");
		thread.setDaemon(true);
		return thread;
	});

	// The table for the currently-connected server, or null when not connected
	// to any server yet. Read from web/insert threads, set from the DB thread.
	private volatile String currentTable;

	public ChatDatabase(Path file) throws Exception {
		Files.createDirectories(file.getParent());
		// Explicit driver load: JDBC's ServiceLoader discovery is unreliable
		// under Fabric's class loader.
		Class.forName("org.sqlite.JDBC");
		connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
		}
	}

	/** Sanitizes a server address/name into a safe SQL table identifier. */
	public static String tableNameFor(String serverKey) {
		String key = (serverKey == null || serverKey.isBlank()) ? "unknown" : serverKey.toLowerCase(Locale.ROOT);
		return "messages_" + key.replaceAll("[^a-z0-9]", "_");
	}

	private void ensureTable(String table) throws SQLException {
		synchronized (connection) {
			try (Statement s = connection.createStatement()) {
				s.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
						+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
						+ "ts INTEGER NOT NULL, server TEXT, type TEXT NOT NULL,"
						+ "sender TEXT, sender_uuid TEXT, plain TEXT NOT NULL, segments TEXT NOT NULL)");
				s.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_sender ON " + table + "(sender)");
				s.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_ts ON " + table + "(ts)");
			}
		}
	}

	/**
	 * Points the log at the given server's table, creating it if needed.
	 * Called when the client joins a server; the actual table create happens on
	 * the DB thread so subsequent inserts land in the right table in order.
	 */
	public void useServer(String serverKey) {
		String table = tableNameFor(serverKey);
		executor.execute(() -> {
			try {
				ensureTable(table);
				currentTable = table;
			} catch (SQLException e) {
				ChatScope.LOGGER.warn("Failed to switch chat log to server '{}'", serverKey, e);
			}
		});
	}

	/** Queues an insert into the current server's table; returns immediately. */
	public void insertAsync(ChatMessage message, String server) {
		executor.execute(() -> {
			String table = currentTable;
			if (table == null) {
				return; // not attached to a server yet
			}
			try {
				synchronized (connection) {
					try (PreparedStatement insert = connection.prepareStatement(
							"INSERT INTO " + table + "(ts, server, type, sender, sender_uuid, plain, segments) VALUES (?,?,?,?,?,?,?)")) {
						insert.setLong(1, message.timestamp());
						insert.setString(2, server);
						insert.setString(3, message.type());
						insert.setString(4, message.sender());
						insert.setString(5, message.senderUuid());
						insert.setString(6, message.plain());
						insert.setString(7, JsonUtil.GSON.toJson(message.segmentsJson()));
						insert.executeUpdate();
					}
				}
			} catch (SQLException e) {
				ChatScope.LOGGER.warn("Failed to store chat message", e);
			}
		});
	}

	// Escapes LIKE wildcards so a name/query is matched literally (\ is the
	// ESCAPE char in the queries below; _ and % are legal in usernames/text).
	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	// The player-matching predicate. Signed chat is matched exactly by sender;
	// plugin-formatted chat falls back to text heuristics ("Name »", "<Name>",
	// "Name:", "Name suspected of"). Placeholders bound by bindPlayer().
	private static final String PLAYER_WHERE = """
			(sender = ?
			 OR plain LIKE '%' || ? || ' »%' ESCAPE '\\'
			 OR plain LIKE '%<' || ? || '>%' ESCAPE '\\'
			 OR plain LIKE ? || ':%' ESCAPE '\\'
			 OR plain LIKE '%' || ? || ' suspected of%' ESCAPE '\\')""";

	private static int bindPlayer(PreparedStatement stmt, String name, String escaped) throws SQLException {
		stmt.setString(1, name);      // exact sender match
		stmt.setString(2, escaped);   // the four LIKE heuristics
		stmt.setString(3, escaped);
		stmt.setString(4, escaped);
		stmt.setString(5, escaped);
		return 6; // next free parameter index
	}

	/**
	 * A page of the current server's logged messages attributable to one
	 * player, newest first, optionally filtered by a text query. Returns
	 * {@code {total, messages}} where total is the full match count.
	 */
	public JsonObject queryByPlayer(String name, int limit, int offset, String query) throws SQLException {
		JsonObject result = new JsonObject();
		JsonArray messages = new JsonArray();
		String table = currentTable;
		if (table == null) {
			result.addProperty("total", 0);
			result.add("messages", messages);
			return result;
		}

		String escaped = escapeLike(name);
		boolean hasQuery = query != null && !query.isBlank();
		String where = PLAYER_WHERE + (hasQuery ? " AND plain LIKE '%' || ? || '%' ESCAPE '\\'" : "");

		synchronized (connection) {
			// Total match count for the pager.
			try (PreparedStatement count = connection.prepareStatement(
					"SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
				int i = bindPlayer(count, name, escaped);
				if (hasQuery) count.setString(i, escapeLike(query));
				try (ResultSet row = count.executeQuery()) {
					row.next();
					result.addProperty("total", row.getInt(1));
				}
			}
			// The requested page.
			try (PreparedStatement page = connection.prepareStatement(
					"SELECT ts, server, type, sender, plain, segments FROM " + table + " WHERE "
							+ where + " ORDER BY ts DESC LIMIT ? OFFSET ?")) {
				int i = bindPlayer(page, name, escaped);
				if (hasQuery) page.setString(i++, escapeLike(query));
				page.setInt(i++, limit);
				page.setInt(i, offset);
				try (ResultSet row = page.executeQuery()) {
					while (row.next()) {
						JsonObject json = new JsonObject();
						json.addProperty("ts", row.getLong("ts"));
						String server = row.getString("server");
						if (server != null) json.addProperty("server", server);
						json.addProperty("type", row.getString("type"));
						String sender = row.getString("sender");
						if (sender != null) json.addProperty("sender", sender);
						json.addProperty("plain", row.getString("plain"));
						json.add("segments", JsonParser.parseString(row.getString("segments")));
						messages.add(json);
					}
				}
			}
		}
		result.add("messages", messages);
		return result;
	}

	/**
	 * Rows for an export of one player's messages, in chronological order.
	 *
	 * When {@code context} is above zero, each of the player's messages also
	 * pulls in that many messages before and after it <em>from everyone</em>,
	 * so the surrounding conversation is included. Overlapping windows are
	 * merged, so nothing is duplicated. Each row carries an {@code own} flag
	 * marking whether it is the player's own message.
	 *
	 * Rows are ordered by id, which equals insertion order (a single writer
	 * thread), so "N messages before/after" is simply an id window.
	 */
	public JsonArray exportWithContext(String name, String query, int context, int limit) throws SQLException {
		JsonArray out = new JsonArray();
		String table = currentTable;
		if (table == null) {
			return out;
		}

		String escaped = escapeLike(name);
		boolean hasQuery = query != null && !query.isBlank();
		String where = PLAYER_WHERE + (hasQuery ? " AND plain LIKE '%' || ? || '%' ESCAPE '\\'" : "");

		synchronized (connection) {
			// 1. The player's own message ids (ascending).
			List<Long> ids = new ArrayList<>();
			try (PreparedStatement ps = connection.prepareStatement(
					"SELECT id FROM " + table + " WHERE " + where + " ORDER BY id ASC")) {
				int i = bindPlayer(ps, name, escaped);
				if (hasQuery) ps.setString(i, escapeLike(query));
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) ids.add(rs.getLong(1));
				}
			}
			if (ids.isEmpty()) {
				return out;
			}
			Set<Long> own = new HashSet<>(ids);

			// 2. Merge the [id-context, id+context] windows into ranges.
			List<long[]> ranges = new ArrayList<>();
			for (long id : ids) {
				long start = id - context;
				long end = id + context;
				long[] last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
				if (last != null && start <= last[1] + 1) {
					last[1] = Math.max(last[1], end);
				} else {
					ranges.add(new long[] { start, end });
				}
			}

			// 3. Read each range back in order.
			try (PreparedStatement ps = connection.prepareStatement(
					"SELECT id, ts, sender, plain FROM " + table + " WHERE id BETWEEN ? AND ? ORDER BY id ASC")) {
				for (long[] range : ranges) {
					if (out.size() >= limit) break;
					ps.setLong(1, range[0]);
					ps.setLong(2, range[1]);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next() && out.size() < limit) {
							JsonObject json = new JsonObject();
							json.addProperty("ts", rs.getLong("ts"));
							String sender = rs.getString("sender");
							if (sender != null) json.addProperty("sender", sender);
							json.addProperty("plain", rs.getString("plain"));
							json.addProperty("own", own.contains(rs.getLong("id")));
							out.add(json);
						}
					}
				}
			}
		}
		return out;
	}

	/**
	 * Every player ever attributed a logged message on the current server,
	 * newest activity first. Candidates for the dashboard's "Offline" tab.
	 */
	public JsonArray knownPlayers() throws SQLException {
		JsonArray players = new JsonArray();
		String table = currentTable;
		if (table == null) {
			return players;
		}
		String sql = "SELECT sender AS name, MAX(sender_uuid) AS uuid, MAX(ts) AS last_seen, COUNT(*) AS msgs "
				+ "FROM " + table + " WHERE sender IS NOT NULL AND sender <> '' "
				+ "GROUP BY sender ORDER BY last_seen DESC";

		synchronized (connection) {
			try (Statement statement = connection.createStatement();
					ResultSet row = statement.executeQuery(sql)) {
				while (row.next()) {
					JsonObject json = new JsonObject();
					json.addProperty("name", row.getString("name"));
					String uuid = row.getString("uuid");
					if (uuid != null) json.addProperty("uuid", uuid);
					json.addProperty("lastSeen", row.getLong("last_seen"));
					json.addProperty("messages", row.getInt("msgs"));
					players.add(json);
				}
			}
		}
		return players;
	}

	@Override
	public void close() {
		executor.shutdown();
		try {
			// Let queued inserts finish so nothing is lost on shutdown.
			if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			synchronized (connection) {
				connection.close();
			}
		} catch (SQLException e) {
			ChatScope.LOGGER.warn("Failed to close chat database cleanly", e);
		}
	}
}
