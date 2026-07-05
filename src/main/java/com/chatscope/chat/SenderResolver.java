package com.chatscope.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.EntityType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * Best-effort recovery of the real username behind plugin-formatted chat.
 *
 * Servers with nickname plugins display a nickname in the visible text, but
 * the interactive metadata usually still carries the true account name:
 * a {@code show_entity} hover (vanilla chat), a {@code /msg <name>} click
 * suggestion, or a {@code show_text} tooltip like "Nootje225's Statistics".
 * We check those in decreasing order of reliability.
 */
public final class SenderResolver {

	public record Sender(String name, String uuid) {
	}

	/** Valid Minecraft account name. */
	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{2,16}$");
	/** "Nootje225's Statistics", "Steve's balance", ... */
	private static final Pattern POSSESSIVE = Pattern.compile("([A-Za-z0-9_]{2,16})'s\\s");
	/** Whisper-style commands whose first argument must be a real username. */
	private static final Pattern MSG_COMMAND = Pattern.compile(
			"^/(?:msg|tell|w|whisper|pm|message|m|profile|stats)\\s+([A-Za-z0-9_]{2,16})\\b",
			Pattern.CASE_INSENSITIVE);

	private SenderResolver() {
	}

	/** The real player behind a message, or {@code null} if undeterminable. */
	public static Sender resolve(Text text) {
		Sender[] byEntity = { null };
		Sender[] byCommand = { null };
		Sender[] byTooltip = { null };

		StringVisitable.StyledVisitor<Object> visitor = (style, content) -> {
			if (byEntity[0] == null
					&& style.getHoverEvent() instanceof HoverEvent.ShowEntity showEntity) {
				HoverEvent.EntityContent entity = showEntity.entity();
				if (entity.entityType == EntityType.PLAYER) {
					String name = entity.name.map(Text::getString).orElse(null);
					if (name != null && NAME.matcher(name).matches()) {
						byEntity[0] = new Sender(name, entity.uuid.toString());
					}
				}
			}

			if (byCommand[0] == null) {
				String command = commandOf(style.getClickEvent());
				if (command != null) {
					Matcher matcher = MSG_COMMAND.matcher(command.trim());
					if (matcher.find()) {
						byCommand[0] = new Sender(matcher.group(1), null);
					}
				}
			}

			if (byTooltip[0] == null
					&& style.getHoverEvent() instanceof HoverEvent.ShowText showText) {
				Matcher matcher = POSSESSIVE.matcher(showText.value().getString());
				if (matcher.find()) {
					byTooltip[0] = new Sender(matcher.group(1), null);
				}
			}

			return Optional.empty();
		};
		text.visit(visitor, Style.EMPTY);

		if (byEntity[0] != null) return byEntity[0];
		if (byCommand[0] != null) return byCommand[0];
		return byTooltip[0];
	}

	private static String commandOf(ClickEvent event) {
		if (event instanceof ClickEvent.RunCommand runCommand) {
			return runCommand.command();
		}
		if (event instanceof ClickEvent.SuggestCommand suggestCommand) {
			return suggestCommand.command();
		}
		return null;
	}
}
