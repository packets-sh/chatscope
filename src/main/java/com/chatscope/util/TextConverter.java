package com.chatscope.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chatscope.model.TextSegment;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

/**
 * Converts Minecraft {@link Text} components into a flat list of styled
 * segments the web frontend can render.
 *
 * Servers (especially plugin-heavy ones) often embed legacy {@code §} color
 * codes as literal characters inside the component text instead of using
 * styles. The in-game font renderer interprets those on the fly, so we must
 * do the same here: each literal run is additionally parsed for {@code §}
 * codes, including the Bungee-style hex form {@code §x§R§R§G§G§B§B}. The
 * codes are stripped from the segment text, so the plain text used for
 * routing, search and copy is clean.
 *
 * Hover events on a run (item tooltips, player/entity info, plain text) are
 * resolved into styled tooltip lines and attached to every segment produced
 * from that run, so the dashboard can show the same tooltips as the game.
 */
public final class TextConverter {

	private static final Map<Character, String> LEGACY_COLORS = Map.ofEntries(
			Map.entry('0', "#000000"), Map.entry('1', "#0000AA"),
			Map.entry('2', "#00AA00"), Map.entry('3', "#00AAAA"),
			Map.entry('4', "#AA0000"), Map.entry('5', "#AA00AA"),
			Map.entry('6', "#FFAA00"), Map.entry('7', "#AAAAAA"),
			Map.entry('8', "#555555"), Map.entry('9', "#5555FF"),
			Map.entry('a', "#55FF55"), Map.entry('b', "#55FFFF"),
			Map.entry('c', "#FF5555"), Map.entry('d', "#FF55FF"),
			Map.entry('e', "#FFFF55"), Map.entry('f', "#FFFFFF")
	);

	private TextConverter() {
	}

	public static List<TextSegment> toSegments(Text text) {
		return toSegments(text, true);
	}

	private static List<TextSegment> toSegments(Text text, boolean includeHover) {
		List<TextSegment> segments = new ArrayList<>();
		// visit() walks the component tree depth-first, handing us each literal
		// run together with its fully-resolved style.
		StringVisitable.StyledVisitor<Object> visitor = (style, content) -> {
			if (!content.isEmpty()) {
				List<List<TextSegment>> hover = includeHover ? hoverLines(style.getHoverEvent()) : null;
				parseLegacy(segments, content, style, hover);
			}
			return Optional.empty(); // keep visiting
		};
		text.visit(visitor, Style.EMPTY);
		return segments;
	}

	/** Plain text of a converted message, with all {@code §} codes stripped. */
	public static String toPlain(List<TextSegment> segments) {
		StringBuilder plain = new StringBuilder();
		for (TextSegment segment : segments) {
			plain.append(segment.text());
		}
		return plain.toString();
	}

	/* ------------------------------------------------------------------ */
	/* Hover events                                                        */
	/* ------------------------------------------------------------------ */

	/**
	 * Resolves a hover event into styled tooltip lines, mirroring what the
	 * in-game tooltip would show. Returns {@code null} when there is nothing
	 * to display.
	 */
	private static List<List<TextSegment>> hoverLines(HoverEvent event) {
		if (event == null) {
			return null;
		}

		List<Text> tooltip;
		if (event instanceof HoverEvent.ShowText showText) {
			tooltip = List.of(showText.value());
		} else if (event instanceof HoverEvent.ShowItem showItem) {
			tooltip = itemTooltip(showItem.item());
		} else if (event instanceof HoverEvent.ShowEntity showEntity) {
			tooltip = showEntity.entity().asTooltip();
		} else {
			return null;
		}

		List<List<TextSegment>> lines = new ArrayList<>();
		for (Text text : tooltip) {
			// Hover of a hover is not a thing; includeHover=false also stops
			// any possible recursion.
			splitIntoLines(lines, toSegments(text, false));
		}
		return lines.isEmpty() ? null : lines;
	}

	/** Full item tooltip as the game would render it (respects F3+H). */
	private static List<Text> itemTooltip(ItemStack stack) {
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			Item.TooltipContext context = client.world != null
					? Item.TooltipContext.create(client.world)
					: Item.TooltipContext.DEFAULT;
			TooltipType type = client.options.advancedItemTooltips
					? TooltipType.ADVANCED
					: TooltipType.BASIC;
			return stack.getTooltip(context, client.player, type);
		} catch (Exception e) {
			// Tooltip assembly touches a lot of game state; never let a broken
			// item break chat forwarding.
			return List.of(stack.getName());
		}
	}

	/** Splits converted segments on embedded newlines into tooltip lines. */
	private static void splitIntoLines(List<List<TextSegment>> out, List<TextSegment> segments) {
		List<TextSegment> current = new ArrayList<>();
		for (TextSegment segment : segments) {
			String[] parts = segment.text().split("\n", -1);
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					out.add(current);
					current = new ArrayList<>();
				}
				if (!parts[i].isEmpty()) {
					current.add(new TextSegment(parts[i], segment.color(), segment.bold(),
							segment.italic(), segment.underline(), segment.strike(),
							segment.obfuscated()));
				}
			}
		}
		out.add(current);
	}

	/* ------------------------------------------------------------------ */
	/* Legacy § code parsing                                               */
	/* ------------------------------------------------------------------ */

	/**
	 * Splits one styled run on embedded legacy {@code §} codes, emitting a
	 * segment per uniform stretch. Follows vanilla semantics: a color code
	 * resets the decoration flags, {@code §r} resets everything to the
	 * component's own style.
	 */
	private static void parseLegacy(List<TextSegment> out, String content, Style base,
			List<List<TextSegment>> hover) {
		String baseColor = colorOf(base);

		String color = baseColor;
		boolean bold = base.isBold();
		boolean italic = base.isItalic();
		boolean underline = base.isUnderlined();
		boolean strike = base.isStrikethrough();
		boolean obfuscated = base.isObfuscated();

		StringBuilder run = new StringBuilder();
		int i = 0;
		while (i < content.length()) {
			char c = content.charAt(i);
			if (c != '§' || i + 1 >= content.length()) {
				run.append(c);
				i++;
				continue;
			}

			char code = Character.toLowerCase(content.charAt(i + 1));
			String legacyColor = LEGACY_COLORS.get(code);
			String hexColor = legacyColor == null && code == 'x' ? readHexColor(content, i) : null;

			if (legacyColor != null || hexColor != null) {
				flush(out, run, color, bold, italic, underline, strike, obfuscated, hover);
				color = legacyColor != null ? legacyColor : hexColor;
				bold = italic = underline = strike = obfuscated = false;
				i += legacyColor != null ? 2 : 14; // §c vs §x§R§R§G§G§B§B
			} else if (code == 'k' || code == 'l' || code == 'm' || code == 'n' || code == 'o') {
				flush(out, run, color, bold, italic, underline, strike, obfuscated, hover);
				switch (code) {
					case 'k' -> obfuscated = true;
					case 'l' -> bold = true;
					case 'm' -> strike = true;
					case 'n' -> underline = true;
					case 'o' -> italic = true;
				}
				i += 2;
			} else if (code == 'r') {
				flush(out, run, color, bold, italic, underline, strike, obfuscated, hover);
				color = baseColor;
				bold = base.isBold();
				italic = base.isItalic();
				underline = base.isUnderlined();
				strike = base.isStrikethrough();
				obfuscated = base.isObfuscated();
				i += 2;
			} else {
				// Unknown code: drop the § but keep the following character
				// visible, mirroring how the vanilla renderer skips it.
				i += 2;
			}
		}
		flush(out, run, color, bold, italic, underline, strike, obfuscated, hover);
	}

	/** Reads {@code §x§R§R§G§G§B§B} starting at {@code start}; null if malformed. */
	private static String readHexColor(String content, int start) {
		if (start + 14 > content.length()) {
			return null;
		}
		StringBuilder hex = new StringBuilder("#");
		for (int j = 0; j < 6; j++) {
			int pos = start + 2 + j * 2;
			char digit = Character.toLowerCase(content.charAt(pos + 1));
			boolean isHex = (digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f');
			if (content.charAt(pos) != '§' || !isHex) {
				return null;
			}
			hex.append(digit);
		}
		return hex.toString().toUpperCase();
	}

	private static void flush(List<TextSegment> out, StringBuilder run,
			String color, boolean bold, boolean italic, boolean underline,
			boolean strike, boolean obfuscated, List<List<TextSegment>> hover) {
		if (run.isEmpty()) {
			return;
		}
		out.add(new TextSegment(run.toString(), color, bold, italic, underline, strike, obfuscated, hover));
		run.setLength(0);
	}

	private static String colorOf(Style style) {
		TextColor color = style.getColor();
		if (color == null) {
			return null;
		}
		return String.format("#%06X", color.getRgb() & 0xFFFFFF);
	}
}
