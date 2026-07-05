package com.chatscope.websocket;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * One WebSocket connection to a browser tab. The dashboard protocol is
 * one-directional (server to browser); the only client message we honor is a
 * lightweight application-level ping used by the frontend as a keepalive.
 */
public class DashboardSocket extends NanoWSD.WebSocket {

	private final ClientRegistry registry;

	public DashboardSocket(NanoHTTPD.IHTTPSession handshakeRequest, ClientRegistry registry) {
		super(handshakeRequest);
		this.registry = registry;
	}

	@Override
	protected void onOpen() {
		registry.onOpen(this);
	}

	@Override
	protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
		registry.onClose(this);
	}

	@Override
	protected void onMessage(NanoWSD.WebSocketFrame message) {
		if ("ping".equals(message.getTextPayload())) {
			try {
				send("{\"type\":\"pong\"}");
			} catch (IOException ignored) {
				// The broken socket will be reaped by the registry's pinger.
			}
		}
	}

	@Override
	protected void onPong(NanoWSD.WebSocketFrame pong) {
		// Browsers answer our protocol pings automatically; nothing to track.
	}

	@Override
	protected void onException(IOException exception) {
		registry.onClose(this);
	}
}
