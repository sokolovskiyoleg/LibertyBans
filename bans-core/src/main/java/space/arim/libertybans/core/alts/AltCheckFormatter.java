/*
 * LibertyBans
 * Copyright © 2026 Anand Beh
 *
 * LibertyBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * LibertyBans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with LibertyBans. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU Affero General Public License.
 */

package space.arim.libertybans.core.alts;

import jakarta.inject.Inject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import space.arim.api.jsonchat.adventure.util.Adventure5Compat;
import space.arim.api.jsonchat.adventure.util.ComponentText;
import space.arim.libertybans.api.NetworkAddress;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.core.config.Configs;
import space.arim.libertybans.core.config.InternalFormatter;
import space.arim.libertybans.core.database.pagination.InstantThenUUID;
import space.arim.libertybans.core.database.pagination.KeysetPage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class AltCheckFormatter {

	private final Configs configs;
	private final InternalFormatter formatter;
	private final Adventure5Compat adventure5Compat;

	@Inject
	public AltCheckFormatter(Configs configs, InternalFormatter formatter, Adventure5Compat adventure5Compat) {
		this.configs = configs;
		this.formatter = formatter;
        this.adventure5Compat = adventure5Compat;
    }

	public Component formatMessage(AccountListFormatting formatting,
								   KeysetPage<DetectedAlt, InstantThenUUID> response,
								   String target, int page) {
		return new FormatAccounts<>(adventure5Compat, formatting, response).format(
				target, page, new DetectedAltFormat(configs, formatter)
		);
	}

	public Component formatAutoShowMessage(AltsSection.AutoShow autoShow,
										   KeysetPage<DetectedAlt, InstantThenUUID> response, String target) {
		var groups = new LinkedHashMap<NetworkAddress, List<DetectedAlt>>();
		for (DetectedAlt detectedAlt : response.data()) {
			groups.computeIfAbsent(detectedAlt.address(), key -> new ArrayList<>()).add(detectedAlt);
		}
		var messages = new ArrayList<Component>();
		ComponentText header = autoShow.header();
		if (!header.isEmpty()) {
			messages.add(header.replaceText("%TARGET%", target).asComponent());
		}
		var detectedAltFormat = new DetectedAltFormat(configs, formatter);
		Component copyHint = autoShow.copyHint().asComponent();
		for (var group : groups.entrySet()) {
			if (!messages.isEmpty()) {
				messages.add(Component.newline());
			}
			String address = group.getKey().toString();
			messages.add(Component.text(address + ":")
					.clickEvent(ClickEvent.copyToClipboard(address))
					.hoverEvent(HoverEvent.showText(copyHint)));
			boolean first = true;
			for (DetectedAlt detectedAlt : group.getValue()) {
				if (first) {
					messages.add(Component.newline().append(Component.text("  ")));
				} else {
					messages.add(autoShow.separator());
				}
				messages.add(detectedAltFormat.format(target, detectedAlt, autoShow.layout(), copyHint).asComponent());
				first = false;
			}
		}
		ComponentText footer = autoShow.footer();
		if (!footer.isEmpty()) {
			messages.add(Component.newline());
			messages.add(footer.replaceText("%TARGET%", target).asComponent());
		}
		return Component.empty().children(messages);
	}

	private record DetectedAltFormat(Configs configs, InternalFormatter formatter)
			implements FormatAccounts.ElementFormat<DetectedAlt> {

		private ComponentLike formatUsername(DetectedAlt detectedAlt, Component copyHint) {
			PunishmentType type;
			if (detectedAlt.hasActivePunishment(PunishmentType.BAN)) {
				type = PunishmentType.BAN;
			} else if (detectedAlt.hasActivePunishment(PunishmentType.MUTE)) {
				type = PunishmentType.MUTE;
			} else {
				type = null;
			}
			ComponentText usernameFormat;
			var nameDisplay = configs.getMessagesConfig().alts().formatting().nameDisplay();
			if (type == null) {
				usernameFormat = nameDisplay.notPunished();
			} else {
				usernameFormat = switch (type) {
					case BAN -> nameDisplay.banned();
					case MUTE -> nameDisplay.muted();
					default -> throw new IllegalArgumentException("Punishment type " + type + " not available");
				};
			}
			String username = detectedAlt.latestUsername().orElseGet(
					() -> configs.getMessagesConfig().formatting().victimDisplay().playerNameUnknown()
			);
			Component formattedUsername = usernameFormat.replaceText("%USERNAME%", username).asComponent();
			return copyHint == null ? formattedUsername : formattedUsername
					.clickEvent(ClickEvent.copyToClipboard(username))
					.hoverEvent(HoverEvent.showText(copyHint));
		}

		@Override
		public ComponentLike format(String target, DetectedAlt detectedAlt) {
			return format(target, detectedAlt, configs.getMessagesConfig().alts().formatting().layout(), null);
		}

		private ComponentLike format(String target, DetectedAlt detectedAlt, ComponentText layout, Component copyHint) {
			var formatting = configs.getMessagesConfig().alts().formatting();
			return layout
					.replaceText("%ADDRESS%", detectedAlt.address().toString())
					.replaceText("%RELEVANT_USERID%", detectedAlt.uuid().toString())
					.replaceText("%DATE_RECORDED%", formatter.formatAbsoluteDate(detectedAlt.recorded()))
					.asComponent()
					.replaceText((config) -> {
						Component detectionKind = switch (detectedAlt.detectionKind()) {
							case NORMAL -> formatting.normal();
							case STRICT -> formatting.strict();
						};
						config.matchLiteral("%DETECTION_KIND%").replacement(detectionKind);
					})
					.replaceText((config) -> {
							config.matchLiteral("%RELEVANT_USER%").replacement(
								formatUsername(detectedAlt, copyHint));
					});
		}
	}

}
