"use strict";

/* ============================================================
 * ChatScope frontend.
 * Plain JS, no frameworks. Talks to the mod over one WebSocket.
 *
 * Sections and filters are fully user-configurable (stored in this
 * browser's localStorage). A message is routed to the first enabled
 * section whose pattern matches; anything matching an enabled filter
 * is hidden entirely.
 * ============================================================ */

const MAX_CLIENT_MESSAGES = 1000;
const OTHERS_ID = "__others__";
const CONFIG_KEY = "chatscope-config";

/* ---------- Configuration ---------- */

const DEFAULT_CONFIG = {
	othersName: "Chat",
	sections: [],   // [{ id, name, patterns: [str], enabled }]
	filters: []     // [{ id, name, pattern: str, enabled }]
};

let config = loadConfig();

function loadConfig() {
	try {
		const raw = JSON.parse(localStorage.getItem(CONFIG_KEY));
		if (raw && typeof raw === "object") {
			return {
				othersName: typeof raw.othersName === "string" ? raw.othersName : DEFAULT_CONFIG.othersName,
				sections: Array.isArray(raw.sections) ? raw.sections.map(normalizeSection) : [],
				filters: Array.isArray(raw.filters) ? raw.filters.map(normalizeFilter) : []
			};
		}
	} catch { /* fall through to defaults */ }
	return structuredClone(DEFAULT_CONFIG);
}

function normalizeSection(s) {
	return {
		id: s.id || genId(),
		name: typeof s.name === "string" ? s.name : "Section",
		patterns: Array.isArray(s.patterns) ? s.patterns.filter(p => typeof p === "string") : [],
		enabled: s.enabled !== false
	};
}
function normalizeFilter(f) {
	return {
		id: f.id || genId(),
		name: typeof f.name === "string" ? f.name : "Filter",
		pattern: typeof f.pattern === "string" ? f.pattern : "",
		enabled: f.enabled !== false
	};
}

function saveConfig() {
	localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
}

function genId() {
	return "id" + Math.random().toString(36).slice(2, 9);
}

// Safe regex compile — an invalid pattern simply never matches.
function compile(pattern) {
	try {
		return new RegExp(pattern);
	} catch {
		return null;
	}
}

function compileConfig() {
	for (const s of config.sections) s._res = s.patterns.map(compile);
	for (const f of config.filters) f._re = compile(f.pattern);
}

/** first enabled section id whose pattern matches, OTHERS_ID, or null if filtered. */
function routeMessage(plain) {
	for (const f of config.filters) {
		if (f.enabled && f._re && f._re.test(plain)) return null;
	}
	for (const s of config.sections) {
		if (s.enabled && s._res.some(re => re && re.test(plain))) return s.id;
	}
	return OTHERS_ID;
}

/* ---------- DOM handles ---------- */

const el = {
	wsStatus: document.getElementById("ws-status"),
	uptime: document.getElementById("uptime"),
	viewers: document.getElementById("viewers"),
	actionbar: document.getElementById("actionbar"),
	search: document.getElementById("search"),
	msgCount: document.getElementById("msg-count"),
	sectionsRoot: document.getElementById("sections"),
	infoServer: document.getElementById("info-server"),
	infoStatus: document.getElementById("info-status"),
	infoCount: document.getElementById("info-count"),
	infoYou: document.getElementById("info-you"),
	players: document.getElementById("players"),
	playerSearch: document.getElementById("player-search"),
	ptabOnline: document.getElementById("ptab-online"),
	ptabOffline: document.getElementById("ptab-offline"),
	onlineCount: document.getElementById("online-count"),
	toast: document.getElementById("toast"),
	hoverTip: document.getElementById("hover-tooltip"),
	// player modal
	playerModal: document.getElementById("player-modal"),
	modalClose: document.getElementById("modal-close"),
	skinViewer: document.getElementById("skin-viewer"),
	modalPlayerName: document.getElementById("modal-player-name"),
	modalPlayerMeta: document.getElementById("modal-player-meta"),
	modalMessages: document.getElementById("modal-messages"),
	modalMsgCount: document.getElementById("modal-msg-count"),
	modalSearch: document.getElementById("modal-search"),
	modalPager: document.getElementById("modal-pager"),
	modalPrev: document.getElementById("modal-prev"),
	modalNext: document.getElementById("modal-next"),
	modalPageInfo: document.getElementById("modal-pageinfo"),
	// settings modal
	settingsModal: document.getElementById("settings-modal"),
	settingsClose: document.getElementById("settings-close"),
	sectionsEditor: document.getElementById("sections-editor"),
	filtersEditor: document.getElementById("filters-editor"),
	othersNameInput: document.getElementById("others-name"),
	addSection: document.getElementById("add-section"),
	addFilter: document.getElementById("add-filter"),
	settingsReset: document.getElementById("settings-reset"),
	btnSettings: document.getElementById("btn-settings"),
	btnAccess: document.getElementById("btn-access"),
	btnSound: document.getElementById("btn-sound"),
	btnTheme: document.getElementById("btn-theme"),
	btnExport: document.getElementById("btn-export")
};

/* ---------- Client state ---------- */

let store = [];          // raw messages in arrival order, capped (source of truth)
let lastChatId = 0;      // de-dup guard
let serverStatus = null;
let localPlayer = null;
let soundEnabled = localStorage.getItem("chatscope-sound") !== "off";
let actionbarTimer = null;

// Per-section runtime panes: id -> { root, messages, jumpBtn, countEl, stick, count }
let panes = {};

/* ============================================================
 * Dynamic section panes (built from config)
 * ============================================================ */

const savedCollapsed = new Set(JSON.parse(localStorage.getItem("chatscope-collapsed") || "[]"));
const savedSizes = JSON.parse(localStorage.getItem("chatscope-sizes") || "{}");

// Section descriptors in display order: user sections (enabled) + catch-all.
function activeSectionDescriptors() {
	const list = config.sections.filter(s => s.enabled).map(s => ({ id: s.id, name: s.name }));
	list.push({ id: OTHERS_ID, name: config.othersName || "Chat" });
	return list;
}

function rebuildSections() {
	compileConfig();
	el.sectionsRoot.innerHTML = "";
	panes = {};

	const descriptors = activeSectionDescriptors();
	descriptors.forEach((desc, index) => {
		if (index > 0) {
			const divider = document.createElement("div");
			divider.className = "divider";
			divider.title = "Drag to resize";
			divider.addEventListener("mousedown", startDrag);
			el.sectionsRoot.appendChild(divider);
		}

		const section = document.createElement("div");
		section.className = "chat-section";
		section.dataset.section = desc.id;
		if (savedCollapsed.has(desc.id)) section.classList.add("collapsed");
		if (savedSizes[desc.id] > 0) section.style.flex = `${savedSizes[desc.id]} 1 0px`;

		const header = document.createElement("div");
		header.className = "section-header";
		header.title = "Click to collapse/expand";
		header.innerHTML = '<span class="collapse-icon">▼</span>'
			+ '<span class="section-title"></span>'
			+ '<span class="section-count mono muted"></span>';
		header.querySelector(".section-title").textContent = desc.name;
		section.appendChild(header);

		const body = document.createElement("div");
		body.className = "section-body";
		const messages = document.createElement("div");
		messages.className = "messages";
		const jumpBtn = document.createElement("button");
		jumpBtn.className = "jump-btn";
		jumpBtn.hidden = true;
		jumpBtn.textContent = "▼ New messages";
		body.appendChild(messages);
		body.appendChild(jumpBtn);
		section.appendChild(body);

		el.sectionsRoot.appendChild(section);

		const pane = { root: section, messages, jumpBtn, countEl: header.querySelector(".section-count"),
			header, stick: true, count: 0 };
		panes[desc.id] = pane;

		header.addEventListener("click", () => {
			section.classList.toggle("collapsed");
			persistCollapsed();
			if (!section.classList.contains("collapsed") && pane.stick) scrollToBottom(pane);
		});
		messages.addEventListener("scroll", () => {
			pane.stick = messages.scrollTop + messages.clientHeight >= messages.scrollHeight - 30;
			if (pane.stick) jumpBtn.hidden = true;
		});
		jumpBtn.addEventListener("click", () => { pane.stick = true; scrollToBottom(pane); });
	});

	renderAllMessages();
}

function persistCollapsed() {
	const collapsed = [...document.querySelectorAll(".chat-section.collapsed")].map(x => x.dataset.section);
	localStorage.setItem("chatscope-collapsed", JSON.stringify(collapsed));
}

// Dividers resize the two adjacent sections using pixel heights as grow weights.
function startDrag(downEvent) {
	const divider = downEvent.currentTarget;
	const above = divider.previousElementSibling;
	const below = divider.nextElementSibling;
	if (!above || !below) return;
	if (above.classList.contains("collapsed") || below.classList.contains("collapsed")) return;

	downEvent.preventDefault();
	divider.classList.add("dragging");
	for (const p of Object.values(panes)) {
		if (!p.root.classList.contains("collapsed")) p.root.style.flex = `${p.root.offsetHeight} 1 0px`;
	}

	const startY = downEvent.clientY;
	const startAbove = above.offsetHeight;
	const startBelow = below.offsetHeight;
	const MIN = 60;

	function onMove(moveEvent) {
		let delta = moveEvent.clientY - startY;
		delta = Math.max(-(startAbove - MIN), Math.min(startBelow - MIN, delta));
		above.style.flex = `${startAbove + delta} 1 0px`;
		below.style.flex = `${startBelow - delta} 1 0px`;
	}
	function onUp() {
		divider.classList.remove("dragging");
		document.removeEventListener("mousemove", onMove);
		document.removeEventListener("mouseup", onUp);
		const sizes = {};
		for (const [id, p] of Object.entries(panes)) {
			if (!p.root.classList.contains("collapsed")) sizes[id] = p.root.offsetHeight;
		}
		localStorage.setItem("chatscope-sizes", JSON.stringify(sizes));
	}
	document.addEventListener("mousemove", onMove);
	document.addEventListener("mouseup", onUp);
}

/* ============================================================
 * Message rendering
 * ============================================================ */

function renderAllMessages() {
	for (const p of Object.values(panes)) { p.messages.innerHTML = ""; p.count = 0; p.stick = true; }
	for (const msg of store) renderOne(msg, true);
	for (const p of Object.values(panes)) scrollToBottom(p);
	updateCounts();
}

function addChatMessage(msg, fromHistory) {
	if (msg.id <= lastChatId) return; // duplicate (init snapshot overlap)
	lastChatId = msg.id;
	store.push(msg);
	if (store.length > MAX_CLIENT_MESSAGES) store.shift();
	renderOne(msg, fromHistory);
	updateCounts();
}

function renderOne(msg, fromHistory) {
	const sectionId = routeMessage(msg.plain);
	if (sectionId === null) return; // filtered out
	const pane = panes[sectionId] || panes[OTHERS_ID];
	if (!pane) return;

	const row = buildMessageRow(msg);
	applySearchFilter(row, msg);
	pane.messages.appendChild(row);
	pane.count++;

	if (pane.stick) scrollToBottom(pane);
	else if (!pane.root.classList.contains("collapsed")) pane.jumpBtn.hidden = false;

	if (!fromHistory && soundEnabled && isMention(msg)) playMentionSound();
}

function scrollToBottom(pane) {
	pane.messages.scrollTop = pane.messages.scrollHeight;
	pane.jumpBtn.hidden = true;
}

function updateCounts() {
	let total = 0;
	for (const p of Object.values(panes)) { p.countEl.textContent = String(p.count); total += p.count; }
	const query = el.search.value.trim();
	el.msgCount.textContent = query
		? `${[...document.querySelectorAll(".msg:not(.hidden)")].length}/${total}`
		: `${total} messages`;
}

function buildMessageRow(msg) {
	const row = document.createElement("div");
	row.className = `msg type-${msg.type}`;
	if (isMention(msg)) row.classList.add("mention");
	row.title = "Click to copy";

	const time = document.createElement("span");
	time.className = "time mono";
	time.textContent = formatTime(msg.ts);
	time.title = `${formatDate(msg.ts)} ${formatTime(msg.ts)}`;
	row.appendChild(time);

	const body = document.createElement("span");
	body.className = "body";
	const boundary = rankBoundary(msg.plain);
	const segs = msg.segments && msg.segments.length ? msg.segments : [{ text: msg.plain }];
	let offset = 0;
	for (const seg of segs) {
		const text = seg.text || "";
		if (boundary > offset && boundary < offset + text.length) {
			body.appendChild(buildSegmentSpan(seg, text.slice(0, boundary - offset), false));
			body.appendChild(buildSegmentSpan(seg, text.slice(boundary - offset), true));
		} else {
			body.appendChild(buildSegmentSpan(seg, text, boundary >= 0 && offset >= boundary));
		}
		offset += text.length;
	}

	// Resolved real account name behind a nickname (from hover metadata).
	if (msg.sender && !msg.plain.toLowerCase().includes(msg.sender.toLowerCase())) {
		const tag = document.createElement("span");
		tag.className = "real-name-tag";
		tag.textContent = `(${msg.sender})`;
		tag.title = `Real name: ${msg.sender} — click for profile`;
		tag.addEventListener("click", (e) => {
			e.stopPropagation();
			openPlayerModal({ name: msg.sender, uuid: msg.senderUuid || "", ping: -1 });
		});
		insertAfterNickname(body, tag);
	}
	row.appendChild(body);

	row.addEventListener("click", () => copyMessage(msg));
	return row;
}

function buildSegmentSpan(seg, text, afterRank) {
	const span = document.createElement("span");
	span.textContent = text;
	if (seg.color) span.style.color = seg.color;
	if (seg.bold) span.style.fontWeight = "700";
	if (seg.italic) span.style.fontStyle = "italic";
	const deco = [];
	if (seg.underline) deco.push("underline");
	if (seg.strike) deco.push("line-through");
	if (deco.length) span.style.textDecoration = deco.join(" ");
	if (seg.obfuscated) span.classList.add("seg-obfuscated");
	if (afterRank) span.classList.add("after-rank");
	if (seg.hover && seg.hover.length) {
		span.classList.add("has-hover");
		span.addEventListener("mouseenter", (e) => showHoverTip(seg.hover, e));
		span.addEventListener("mousemove", moveHoverTip);
		span.addEventListener("mouseleave", hideHoverTip);
	}
	return span;
}

// Places the real-name tag right before the first "»" separator.
function insertAfterNickname(body, tag) {
	for (const span of [...body.children]) {
		const text = span.textContent;
		const idx = text.indexOf("»");
		if (idx < 0) continue;
		if (idx === 0) {
			body.insertBefore(tag, span);
		} else {
			const tail = span.cloneNode(false);
			tail.textContent = text.slice(idx);
			span.textContent = text.slice(0, idx);
			span.after(tag, tail);
		}
		return;
	}
	body.appendChild(tag);
}

/**
 * Index where the "rank prefix" ends, or -1. Everything after it is whitened
 * in accessibility mode. Handles "[rank] Name »", vanilla "<name> ", or "»".
 */
function rankBoundary(plain) {
	const vanilla = plain.match(/^<[^>]+>\s*/);
	if (vanilla) return vanilla[0].length;
	const arrow = plain.indexOf("»");
	const head = arrow >= 0 ? plain.slice(0, arrow) : "";
	const lastTag = Math.max(head.lastIndexOf("]"), head.lastIndexOf(")"));
	if (lastTag >= 0) {
		let end = lastTag + 1;
		while (end < plain.length && plain[end] === " ") end++;
		return end;
	}
	if (arrow >= 0) return arrow + 1;
	const bracketed = plain.match(/^(\s*(?:\[[^\]]*\]|\([^)]*\))\s*)+/);
	if (bracketed) return bracketed[0].length;
	return -1;
}

function isMention(msg) {
	if (!localPlayer) return false;
	if (msg.sender === localPlayer) return false;
	// Only the message content counts, not the speaker's own name/rank; and
	// system/command output (all-caps keyword before "»") never pings.
	const arrow = msg.plain.indexOf("»");
	if (arrow >= 0 && !/[a-z]/.test(msg.plain.slice(0, arrow))) return false;
	return new RegExp(`\\b${escapeRegex(localPlayer)}\\b`, "i").test(mentionScope(msg.plain));
}

function mentionScope(plain) {
	const arrow = plain.indexOf("»");
	if (arrow >= 0) return plain.slice(arrow + 1);
	const vanilla = plain.match(/^<[^>]+>\s*/);
	if (vanilla) return plain.slice(vanilla[0].length);
	return plain;
}

function escapeRegex(text) {
	return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function formatTime(ts) {
	const d = new Date(ts);
	const pad = (n) => String(n).padStart(2, "0");
	return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
function formatDate(ts) {
	const d = new Date(ts);
	const pad = (n) => String(n).padStart(2, "0");
	return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function copyMessage(msg) {
	navigator.clipboard.writeText(`[${formatTime(msg.ts)}] ${msg.plain}`)
		.then(() => showToast("Copied to clipboard"))
		.catch(() => showToast("Copy failed"));
}

/* ---------- Search ---------- */

el.search.addEventListener("input", () => {
	for (const p of Object.values(panes)) {
		for (const row of p.messages.children) applySearchFilterByText(row);
	}
	updateCounts();
});

function applySearchFilter(row, msg) {
	const query = el.search.value.trim().toLowerCase();
	row.classList.toggle("hidden", query !== "" && !msg.plain.toLowerCase().includes(query));
}
function applySearchFilterByText(row) {
	const query = el.search.value.trim().toLowerCase();
	row.classList.toggle("hidden", query !== "" && !row.textContent.toLowerCase().includes(query));
}

/* ============================================================
 * Minecraft-style hover tooltips
 * ============================================================ */

function showHoverTip(lines, event) {
	el.hoverTip.innerHTML = "";
	for (const line of lines) {
		const div = document.createElement("div");
		div.className = "tip-line";
		for (const seg of line) div.appendChild(buildSegmentSpan(seg, seg.text || "", false));
		el.hoverTip.appendChild(div);
	}
	el.hoverTip.hidden = false;
	moveHoverTip(event);
}
function moveHoverTip(event) {
	const tip = el.hoverTip;
	if (tip.hidden) return;
	let x = event.clientX + 14;
	let y = event.clientY - tip.offsetHeight - 10;
	if (y < 4) y = event.clientY + 18;
	if (x + tip.offsetWidth > window.innerWidth - 4) x = window.innerWidth - tip.offsetWidth - 4;
	tip.style.left = `${x}px`;
	tip.style.top = `${y}px`;
}
function hideHoverTip() { el.hoverTip.hidden = true; }

/* ============================================================
 * Action bar
 * ============================================================ */

function showActionBar(data) {
	el.actionbar.hidden = false;
	el.actionbar.textContent = data.plain;
	el.actionbar.style.opacity = "1";
	clearTimeout(actionbarTimer);
	actionbarTimer = setTimeout(() => { el.actionbar.style.opacity = "0"; }, 4000);
}

/* ============================================================
 * WebSocket connection
 * ============================================================ */

let ws = null;
let pingTimer = null;

function connect() {
	const wsProtocol = location.protocol === "https:" ? "wss:" : "ws:";
	ws = new WebSocket(`${wsProtocol}//${location.host}/ws`);
	ws.onopen = () => {
		setWsStatus(true);
		pingTimer = setInterval(() => { if (ws && ws.readyState === WebSocket.OPEN) ws.send("ping"); }, 25000);
	};
	ws.onmessage = (event) => {
		let envelope;
		try { envelope = JSON.parse(event.data); } catch { return; }
		handleMessage(envelope);
	};
	ws.onclose = () => { setWsStatus(false); clearInterval(pingTimer); setTimeout(connect, 2000); };
	ws.onerror = () => ws.close();
}

function setWsStatus(connected) {
	el.wsStatus.textContent = connected ? "live" : "reconnecting…";
	el.wsStatus.className = connected ? "pill pill-good" : "pill pill-bad";
}

function handleMessage(envelope) {
	const data = envelope.data;
	switch (envelope.type) {
		case "init":
			resetChat();
			renderServer(data.server);
			renderPlayers(data.players || []);
			for (const msg of data.history || []) addChatMessage(msg, true);
			updateCounts();
			break;
		case "chat": addChatMessage(data, false); updateCounts(); break;
		case "actionbar": showActionBar(data); break;
		case "players": renderPlayers(data.players || []); break;
		case "server": renderServer(data.server); break;
		case "viewers":
			el.viewers.textContent = `👁 ${data.count}`;
			el.viewers.title = `${data.count} ${data.count === 1 ? "person" : "people"} currently viewing this dashboard`;
			break;
		case "pong": break;
	}
}

function resetChat() {
	store = [];
	lastChatId = 0;
	for (const p of Object.values(panes)) { p.messages.innerHTML = ""; p.count = 0; p.stick = true; }
}

/* ============================================================
 * Server info + player list
 * ============================================================ */

let onlinePlayers = [];
let offlinePlayers = [];
let activePlayerTab = "online";

function renderServer(server) {
	serverStatus = server || { connected: false, kind: "disconnected" };
	localPlayer = serverStatus.localPlayer || localPlayer;
	if (serverStatus.connected) {
		const label = serverStatus.kind === "singleplayer" ? "Singleplayer"
			: serverStatus.kind === "realm" ? "Realms"
			: (serverStatus.address || serverStatus.name || "Unknown");
		el.infoServer.textContent = label;
		el.infoServer.title = serverStatus.name && serverStatus.address && serverStatus.name !== serverStatus.address
			? `${serverStatus.name} (${serverStatus.address})` : label;
		el.infoStatus.textContent = "Connected";
		el.infoStatus.className = "info-value status-connected";
	} else {
		el.infoServer.textContent = "—";
		el.infoServer.title = "";
		el.infoStatus.textContent = "Disconnected";
		el.infoStatus.className = "info-value status-disconnected";
		el.infoCount.textContent = "—";
	}
	el.infoYou.textContent = serverStatus.localPlayer || "—";
	updateUptime();
}

function renderPlayers(players) {
	onlinePlayers = players;
	el.infoCount.textContent = serverStatus && serverStatus.connected ? String(players.length) : "—";
	el.onlineCount.textContent = String(players.length);
	renderPlayerList();
}

function renderPlayerList() {
	const offline = activePlayerTab === "offline";
	const query = el.playerSearch.value.trim().toLowerCase();
	let list = offline ? offlinePlayers : onlinePlayers;
	if (query) list = list.filter(p => p.name.toLowerCase().includes(query));

	el.players.innerHTML = "";
	if (list.length === 0) {
		const note = document.createElement("div");
		note.className = "empty-note";
		note.textContent = offline
			? (query ? "No offline players match" : "No known players yet")
			: (query ? "No online players match"
				: (serverStatus && serverStatus.connected ? "No players visible" : "Not connected"));
		el.players.appendChild(note);
		return;
	}
	for (const player of list) el.players.appendChild(buildPlayerRow(player, offline));
}

function buildPlayerRow(player, offline) {
	const row = document.createElement("div");
	row.className = "player";
	row.title = `UUID: ${player.uuid || "unknown"}\nClick for profile & message history`;
	row.addEventListener("click", () => openPlayerModal(player));

	const img = document.createElement("img");
	img.alt = "";
	img.loading = "lazy";
	if (player.uuid) {
		img.src = `https://crafatar.com/avatars/${player.uuid}?size=48&overlay`;
		img.onerror = () => { img.onerror = null; img.src = `https://mc-heads.net/avatar/${encodeURIComponent(player.name)}/48`; };
	} else {
		img.src = `https://mc-heads.net/avatar/${encodeURIComponent(player.name)}/48`;
	}
	row.appendChild(img);

	const name = document.createElement("span");
	name.className = "pname";
	name.textContent = player.name;
	if (localPlayer && player.name === localPlayer) {
		const badge = document.createElement("span");
		badge.className = "you-badge";
		badge.textContent = "YOU";
		name.appendChild(badge);
	}
	row.appendChild(name);

	if (offline) {
		const seen = document.createElement("span");
		seen.className = "lastseen mono";
		seen.textContent = player.lastSeen ? timeAgo(player.lastSeen) : "";
		seen.title = player.lastSeen ? new Date(player.lastSeen).toLocaleString() : "";
		row.appendChild(seen);
	} else {
		if (player.gameMode && player.gameMode !== "survival") {
			const mode = document.createElement("span");
			mode.className = "gamemode";
			mode.textContent = player.gameMode;
			row.appendChild(mode);
		}
		row.appendChild(buildPing(player.ping));
	}
	return row;
}

function buildPing(ping) {
	const wrap = document.createElement("span");
	wrap.className = "ping";
	const ms = document.createElement("span");
	ms.className = "ms mono";
	ms.textContent = ping >= 0 ? `${ping}ms` : "?";
	wrap.appendChild(ms);
	const bars = document.createElement("span");
	const level = ping < 0 ? 0 : ping < 50 ? 5 : ping < 100 ? 4 : ping < 150 ? 3 : ping < 250 ? 2 : 1;
	bars.className = "bars" + (level <= 1 ? " bad" : level <= 2 ? " poor" : "");
	for (let i = 1; i <= 5; i++) {
		const bar = document.createElement("i");
		if (i <= level) bar.className = "on";
		bars.appendChild(bar);
	}
	wrap.appendChild(bars);
	return wrap;
}

function timeAgo(ts) {
	const s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
	if (s < 60) return `${s}s ago`;
	const m = Math.floor(s / 60);
	if (m < 60) return `${m}m ago`;
	const h = Math.floor(m / 60);
	if (h < 24) return `${h}h ago`;
	return `${Math.floor(h / 24)}d ago`;
}

async function loadOfflinePlayers() {
	try {
		const response = await fetch("/api/players");
		const data = await response.json();
		const online = new Set(onlinePlayers.map(p => p.name.toLowerCase()));
		offlinePlayers = (data.players || []).filter(p => !online.has(p.name.toLowerCase()));
	} catch { offlinePlayers = []; }
	if (activePlayerTab === "offline") renderPlayerList();
}

for (const btn of [el.ptabOnline, el.ptabOffline]) {
	btn.addEventListener("click", () => {
		activePlayerTab = btn.dataset.ptab;
		el.ptabOnline.classList.toggle("active", activePlayerTab === "online");
		el.ptabOffline.classList.toggle("active", activePlayerTab === "offline");
		if (activePlayerTab === "offline") loadOfflinePlayers();
		renderPlayerList();
	});
}
el.playerSearch.addEventListener("input", renderPlayerList);

/* ============================================================
 * Player profile modal (history + 3D skin)
 * ============================================================ */

const MODAL_PAGE_SIZE = 500;
let modalPlayer = null;
let modalPage = 0;
let modalQuery = "";
let modalTotal = 0;
let modalSearchTimer = null;

function openPlayerModal(player) {
	el.playerModal.hidden = false;
	el.modalPlayerName.textContent = player.name;
	el.modalPlayerMeta.textContent = "";
	const meta = [];
	if (player.uuid) meta.push(player.uuid);
	if (typeof player.ping === "number") {
		meta.push(`ping ${player.ping >= 0 ? player.ping + "ms" : "?"}` + (player.gameMode ? ` · ${player.gameMode}` : ""));
	}
	if (player.lastSeen) {
		meta.push(`Last online: ${new Date(player.lastSeen).toLocaleString()} (${timeAgo(player.lastSeen)})`);
	}
	for (const line of meta) {
		const div = document.createElement("div");
		div.textContent = line;
		el.modalPlayerMeta.appendChild(div);
	}
	startSkinViewer(player);

	modalPlayer = player;
	modalPage = 0;
	modalQuery = "";
	el.modalSearch.value = "";
	loadHistoryPage();
}

async function loadHistoryPage() {
	const player = modalPlayer;
	if (!player) return;
	el.modalMessages.innerHTML = "";
	const loading = document.createElement("div");
	loading.className = "empty-note";
	loading.textContent = "Loading…";
	el.modalMessages.appendChild(loading);

	const url = `/api/player?name=${encodeURIComponent(player.name)}`
		+ `&limit=${MODAL_PAGE_SIZE}&offset=${modalPage * MODAL_PAGE_SIZE}`
		+ (modalQuery ? `&q=${encodeURIComponent(modalQuery)}` : "");
	try {
		const response = await fetch(url);
		const data = await response.json();
		if (data.error) throw new Error(data.error);
		if (modalPlayer !== player) return;
		modalTotal = data.total || 0;
		renderModalMessages(data.messages || []);
		updatePager();
	} catch (err) {
		el.modalMessages.innerHTML = "";
		const note = document.createElement("div");
		note.className = "empty-note";
		note.textContent = `Could not load history (${err.message})`;
		el.modalMessages.appendChild(note);
		el.modalPager.hidden = true;
	}
}

function renderModalMessages(messages) {
	el.modalMessages.innerHTML = "";
	if (messages.length === 0) {
		const note = document.createElement("div");
		note.className = "empty-note";
		note.textContent = modalQuery ? "No messages match your search" : "No logged messages from this player yet";
		el.modalMessages.appendChild(note);
		return;
	}
	// API returns newest first; show chronologically.
	for (const msg of messages.slice().reverse()) {
		const row = buildMessageRow(msg);
		row.querySelector(".time").textContent = `${formatDate(msg.ts)} ${formatTime(msg.ts)}`;
		el.modalMessages.appendChild(row);
	}
	el.modalMessages.scrollTop = el.modalMessages.scrollHeight;
}

function updatePager() {
	const pages = Math.max(1, Math.ceil(modalTotal / MODAL_PAGE_SIZE));
	el.modalPager.hidden = pages <= 1;
	el.modalPrev.disabled = modalPage <= 0;
	el.modalNext.disabled = modalPage >= pages - 1;
	const from = modalTotal === 0 ? 0 : modalPage * MODAL_PAGE_SIZE + 1;
	const to = Math.min(modalTotal, (modalPage + 1) * MODAL_PAGE_SIZE);
	el.modalPageInfo.textContent = `${from}–${to} of ${modalTotal}`;
	el.modalMsgCount.textContent = modalQuery
		? `${modalTotal} match${modalTotal === 1 ? "" : "es"}`
		: `${modalTotal} total`;
}

el.modalSearch.addEventListener("input", () => {
	clearTimeout(modalSearchTimer);
	modalSearchTimer = setTimeout(() => { modalQuery = el.modalSearch.value.trim(); modalPage = 0; loadHistoryPage(); }, 250);
});
el.modalPrev.addEventListener("click", () => { if (modalPage > 0) { modalPage--; loadHistoryPage(); } });
el.modalNext.addEventListener("click", () => { modalPage++; loadHistoryPage(); });

function closePlayerModal() { el.playerModal.hidden = true; stopSkinViewer(); }
el.modalClose.addEventListener("click", closePlayerModal);
el.playerModal.addEventListener("click", (e) => { if (e.target === el.playerModal) closePlayerModal(); });
document.addEventListener("keydown", (e) => {
	if (e.key !== "Escape") return;
	if (!el.playerModal.hidden) closePlayerModal();
	else if (!el.settingsModal.hidden) el.settingsModal.hidden = true;
});

/* ============================================================
 * 3D skin viewer — pure CSS 3D, no libraries
 * ============================================================ */

const SKIN_SCALE = 6;
let skinAnimation = null;

const SKIN_PARTS = [
	{ size: [8, 8, 8], center: [0, -12], uv: [0, 0], overlay: [32, 0] },
	{ size: [8, 12, 4], center: [0, -2], uv: [16, 16], overlay: [16, 32] },
	{ size: [4, 12, 4], center: [-6, -2], uv: [40, 16], overlay: [40, 32] },
	{ size: [4, 12, 4], center: [6, -2], uv: [32, 48], overlay: [48, 48] },
	{ size: [4, 12, 4], center: [-2, 10], uv: [0, 16], overlay: [0, 32] },
	{ size: [4, 12, 4], center: [2, 10], uv: [16, 48], overlay: [0, 48] }
];

function startSkinViewer(player) {
	stopSkinViewer();
	el.skinViewer.innerHTML = "";
	const model = document.createElement("div");
	model.className = "skin-model";
	el.skinViewer.appendChild(model);

	resolveSkinUrl(player).then((skinUrl) => {
		for (const part of SKIN_PARTS) {
			model.appendChild(buildCuboid(skinUrl, part.uv, part.size, part.center, 0));
			model.appendChild(buildCuboid(skinUrl, part.overlay, part.size, part.center, 0.5));
		}
	});

	let yaw = -30, pitch = -10, dragging = false, lastInteraction = 0;
	const apply = () => { model.style.transform = `rotateX(${pitch}deg) rotateY(${yaw}deg)`; };

	el.skinViewer.onpointerdown = (e) => { dragging = true; el.skinViewer.setPointerCapture(e.pointerId); e.preventDefault(); };
	el.skinViewer.onpointermove = (e) => {
		if (!dragging) return;
		yaw += e.movementX * 0.6;
		pitch = Math.max(-60, Math.min(60, pitch - e.movementY * 0.4));
		lastInteraction = performance.now();
		apply();
	};
	el.skinViewer.onpointerup = el.skinViewer.onpointercancel = () => { dragging = false; lastInteraction = performance.now(); };

	const spin = (now) => {
		if (!dragging && now - lastInteraction > 2500) { yaw += 0.35; apply(); }
		skinAnimation = requestAnimationFrame(spin);
	};
	apply();
	skinAnimation = requestAnimationFrame(spin);
}

function stopSkinViewer() {
	if (skinAnimation !== null) { cancelAnimationFrame(skinAnimation); skinAnimation = null; }
	el.skinViewer.innerHTML = "";
}

function resolveSkinUrl(player) {
	return new Promise((resolve) => {
		if (!player.uuid) { resolve(`https://mc-heads.net/skin/${encodeURIComponent(player.name)}`); return; }
		const url = `https://crafatar.com/skins/${player.uuid}`;
		const probe = new Image();
		probe.onload = () => resolve(url);
		probe.onerror = () => resolve(`https://mc-heads.net/skin/${encodeURIComponent(player.name)}`);
		probe.src = url;
	});
}

function buildCuboid(skinUrl, uv, [w, h, d], [cx, cy], inflate) {
	const S = SKIN_SCALE;
	const box = document.createElement("div");
	box.className = inflate ? "skin-overlay-wrap" : "skin-part";
	const scale = inflate ? ` scale3d(${(w + inflate * 2) / w}, ${(h + inflate * 2) / h}, ${(d + inflate * 2) / d})` : "";
	box.style.transform = `translate3d(${cx * S}px, ${cy * S}px, 0)${scale}`;

	const faces = [
		[uv[0] + d, uv[1], w, d, `rotateX(90deg) translateZ(${h / 2 * S}px)`],
		[uv[0] + d + w, uv[1], w, d, `rotateX(-90deg) translateZ(${h / 2 * S}px)`],
		[uv[0], uv[1] + d, d, h, `rotateY(-90deg) translateZ(${w / 2 * S}px)`],
		[uv[0] + d, uv[1] + d, w, h, `translateZ(${d / 2 * S}px)`],
		[uv[0] + d + w, uv[1] + d, d, h, `rotateY(90deg) translateZ(${w / 2 * S}px)`],
		[uv[0] + d + w + d, uv[1] + d, w, h, `rotateY(180deg) translateZ(${d / 2 * S}px)`]
	];
	for (const [u, v, fw, fh, orientation] of faces) {
		const face = document.createElement("div");
		face.className = "skin-face";
		face.style.width = `${fw * S}px`;
		face.style.height = `${fh * S}px`;
		face.style.marginLeft = `${-fw * S / 2}px`;
		face.style.marginTop = `${-fh * S / 2}px`;
		face.style.backgroundImage = `url("${skinUrl}")`;
		face.style.backgroundSize = `${64 * S}px ${64 * S}px`;
		face.style.backgroundPosition = `${-u * S}px ${-v * S}px`;
		face.style.transform = orientation;
		box.appendChild(face);
	}
	return box;
}

/* ============================================================
 * Uptime ticker
 * ============================================================ */

function updateUptime() {
	if (!serverStatus || !serverStatus.connected || !serverStatus.connectedSince) { el.uptime.textContent = ""; return; }
	let seconds = Math.max(0, Math.floor((Date.now() - serverStatus.connectedSince) / 1000));
	const h = Math.floor(seconds / 3600);
	const m = Math.floor((seconds % 3600) / 60);
	const s = seconds % 60;
	el.uptime.textContent = "up " + (h > 0 ? `${h}h ${m}m ${s}s` : m > 0 ? `${m}m ${s}s` : `${s}s`);
}
setInterval(updateUptime, 1000);

/* ============================================================
 * Toolbar buttons
 * ============================================================ */

/* Theme */
if (localStorage.getItem("chatscope-theme") === "light") document.documentElement.dataset.theme = "light";
el.btnTheme.addEventListener("click", () => {
	const light = document.documentElement.dataset.theme === "light";
	if (light) { delete document.documentElement.dataset.theme; localStorage.removeItem("chatscope-theme"); }
	else { document.documentElement.dataset.theme = "light"; localStorage.setItem("chatscope-theme", "light"); }
});

/* Accessibility */
let accessMode = localStorage.getItem("chatscope-access") === "on";
document.body.classList.toggle("accessibility", accessMode);
el.btnAccess.classList.toggle("off", !accessMode);
el.btnAccess.addEventListener("click", () => {
	accessMode = !accessMode;
	localStorage.setItem("chatscope-access", accessMode ? "on" : "off");
	document.body.classList.toggle("accessibility", accessMode);
	el.btnAccess.classList.toggle("off", !accessMode);
	showToast(accessMode ? "Accessibility mode on" : "Accessibility mode off");
});

/* Mention sound */
el.btnSound.classList.toggle("off", !soundEnabled);
el.btnSound.addEventListener("click", () => {
	soundEnabled = !soundEnabled;
	localStorage.setItem("chatscope-sound", soundEnabled ? "on" : "off");
	el.btnSound.classList.toggle("off", !soundEnabled);
	if (soundEnabled) playMentionSound();
	showToast(soundEnabled ? "Mention sound on" : "Mention sound off");
});

let audioCtx = null;
function playMentionSound() {
	try {
		audioCtx = audioCtx || new AudioContext();
		if (audioCtx.state === "suspended") audioCtx.resume();
		const now = audioCtx.currentTime;
		for (const [offset, freq] of [[0, 880], [0.12, 1174.66]]) {
			const osc = audioCtx.createOscillator();
			const gain = audioCtx.createGain();
			osc.frequency.value = freq;
			osc.type = "sine";
			gain.gain.setValueAtTime(0.001, now + offset);
			gain.gain.exponentialRampToValueAtTime(0.15, now + offset + 0.02);
			gain.gain.exponentialRampToValueAtTime(0.001, now + offset + 0.25);
			osc.connect(gain).connect(audioCtx.destination);
			osc.start(now + offset);
			osc.stop(now + offset + 0.3);
		}
	} catch { /* blocked until first interaction */ }
}

/* Export */
el.btnExport.addEventListener("click", () => {
	const lines = store.map(msg => `[${new Date(msg.ts).toISOString()}] [${msg.type}] ${msg.plain}`);
	const blob = new Blob([lines.join("\n")], { type: "text/plain" });
	const link = document.createElement("a");
	link.href = URL.createObjectURL(blob);
	link.download = `chatscope-${new Date().toISOString().replace(/[:.]/g, "-")}.txt`;
	link.click();
	URL.revokeObjectURL(link.href);
	showToast("Chat history exported");
});

/* ---------- Toast ---------- */
let toastTimer = null;
function showToast(text) {
	el.toast.textContent = text;
	el.toast.hidden = false;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => { el.toast.hidden = true; }, 1800);
}

/* ============================================================
 * Settings UI (configure sections & filters)
 * ============================================================ */

el.btnSettings.addEventListener("click", () => { renderSettingsEditors(); el.settingsModal.hidden = false; });
el.settingsClose.addEventListener("click", () => { el.settingsModal.hidden = true; });
el.settingsModal.addEventListener("click", (e) => { if (e.target === el.settingsModal) el.settingsModal.hidden = true; });

let rebuildTimer = null;
function applyConfig(rebuild = true) {
	saveConfig();
	if (rebuild) {
		clearTimeout(rebuildTimer);
		rebuildTimer = setTimeout(rebuildSections, 120);
	}
}

el.othersNameInput.addEventListener("input", () => { config.othersName = el.othersNameInput.value || "Chat"; applyConfig(); });
el.addSection.addEventListener("click", () => {
	config.sections.push({ id: genId(), name: "New section", patterns: [""], enabled: true });
	renderSettingsEditors();
	applyConfig();
});
el.addFilter.addEventListener("click", () => {
	config.filters.push({ id: genId(), name: "New filter", pattern: "", enabled: true });
	renderSettingsEditors();
	applyConfig();
});
el.settingsReset.addEventListener("click", () => {
	if (!confirm("Reset all sections and filters to defaults?")) return;
	config = structuredClone(DEFAULT_CONFIG);
	renderSettingsEditors();
	applyConfig();
});

function renderSettingsEditors() {
	el.othersNameInput.value = config.othersName || "Chat";

	el.sectionsEditor.innerHTML = "";
	if (config.sections.length === 0) el.sectionsEditor.appendChild(emptyNote("No sections yet — everything goes to the catch-all."));
	config.sections.forEach((section, index) => el.sectionsEditor.appendChild(buildSectionEditor(section, index)));

	el.filtersEditor.innerHTML = "";
	if (config.filters.length === 0) el.filtersEditor.appendChild(emptyNote("No filters yet."));
	config.filters.forEach((filter) => el.filtersEditor.appendChild(buildFilterEditor(filter)));
}

function emptyNote(text) {
	const div = document.createElement("div");
	div.className = "editor-empty";
	div.textContent = text;
	return div;
}

function buildSectionEditor(section, index) {
	const card = document.createElement("div");
	card.className = "editor-card";

	const head = document.createElement("div");
	head.className = "editor-head";

	const enabled = document.createElement("input");
	enabled.type = "checkbox";
	enabled.checked = section.enabled;
	enabled.title = "Enable/disable this section";
	enabled.addEventListener("change", () => { section.enabled = enabled.checked; applyConfig(); });

	const name = document.createElement("input");
	name.type = "text";
	name.className = "editor-name";
	name.value = section.name;
	name.placeholder = "Section name";
	name.addEventListener("input", () => { section.name = name.value; applyConfig(); });

	const up = iconMini("↑", "Move up", () => { moveItem(config.sections, index, -1); renderSettingsEditors(); applyConfig(); });
	const down = iconMini("↓", "Move down", () => { moveItem(config.sections, index, 1); renderSettingsEditors(); applyConfig(); });
	const del = iconMini("✕", "Delete section", () => { config.sections.splice(index, 1); renderSettingsEditors(); applyConfig(); });
	del.classList.add("danger");

	head.append(enabled, name, up, down, del);

	const patterns = document.createElement("textarea");
	patterns.className = "editor-patterns";
	patterns.rows = Math.max(2, section.patterns.length);
	patterns.value = section.patterns.join("\n");
	patterns.placeholder = "One regex per line, e.g.  ^\\[Staff\\]";
	patterns.spellcheck = false;
	patterns.addEventListener("input", () => {
		section.patterns = patterns.value.split("\n").map(s => s.trim()).filter(Boolean);
		markRegexValidity(patterns, section.patterns);
		applyConfig();
	});
	markRegexValidity(patterns, section.patterns);

	card.append(head, patterns);
	return card;
}

function buildFilterEditor(filter) {
	const card = document.createElement("div");
	card.className = "editor-card";
	const head = document.createElement("div");
	head.className = "editor-head";

	const enabled = document.createElement("input");
	enabled.type = "checkbox";
	enabled.checked = filter.enabled;
	enabled.title = "Enable/disable this filter";
	enabled.addEventListener("change", () => { filter.enabled = enabled.checked; applyConfig(); });

	const name = document.createElement("input");
	name.type = "text";
	name.className = "editor-name";
	name.value = filter.name;
	name.placeholder = "Filter name";
	name.addEventListener("input", () => { filter.name = name.value; applyConfig(false); });

	const del = iconMini("✕", "Delete filter", () => {
		config.filters = config.filters.filter(f => f !== filter);
		renderSettingsEditors();
		applyConfig();
	});
	del.classList.add("danger");
	head.append(enabled, name, del);

	const pattern = document.createElement("input");
	pattern.type = "text";
	pattern.className = "editor-patterns";
	pattern.value = filter.pattern;
	pattern.placeholder = "Regex to hide, e.g.  ^SHOP »";
	pattern.spellcheck = false;
	pattern.addEventListener("input", () => {
		filter.pattern = pattern.value.trim();
		markRegexValidity(pattern, [filter.pattern]);
		applyConfig();
	});
	markRegexValidity(pattern, [filter.pattern]);

	card.append(head, pattern);
	return card;
}

function iconMini(text, title, onClick) {
	const b = document.createElement("button");
	b.className = "mini-icon";
	b.textContent = text;
	b.title = title;
	b.addEventListener("click", onClick);
	return b;
}

function moveItem(arr, index, dir) {
	const j = index + dir;
	if (j < 0 || j >= arr.length) return;
	[arr[index], arr[j]] = [arr[j], arr[index]];
}

// Red border when any pattern is an invalid regex.
function markRegexValidity(input, patterns) {
	input.classList.toggle("invalid", patterns.some(p => p && compile(p) === null));
}

/* ============================================================
 * Boot
 * ============================================================ */

rebuildSections();
renderServer(null);
renderPlayers([]);
updateCounts();
connect();
