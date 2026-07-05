package com.chatscope.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import me.shedaniel.autoconfig.AutoConfig;

/**
 * Surfaces the ChatScope settings screen in Mod Menu's mod list. Only loaded
 * when Mod Menu is installed; the config still works without it via the JSON
 * file and the in-mod defaults.
 */
public class ChatScopeModMenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> AutoConfig.getConfigScreen(ChatScopeConfig.class, parent).get();
	}
}
