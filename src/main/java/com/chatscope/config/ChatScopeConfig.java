package com.chatscope.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * Persisted mod settings, editable in-game through Mod Menu (or by hand at
 * {@code config/chatscope.json}).
 */
@Config(name = "chatscope")
public class ChatScopeConfig implements ConfigData {

	/** Password required to view the dashboard. Empty means no password. */
	@ConfigEntry.Gui.Tooltip
	public String password = "";
}
