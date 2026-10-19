/*
 * LibertyBans
 * Copyright © 2026 Anand Beh
 *
 * LibertyBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

package space.arim.libertybans.core.config;

import net.kyori.adventure.text.Component;
import space.arim.api.jsonchat.adventure.util.ComponentText;
import space.arim.dazzleconf.annote.ConfDefault;
import space.arim.dazzleconf.annote.ConfHeader;
import space.arim.dazzleconf.annote.SubSection;

@ConfHeader("Messages for /check")
public interface CheckSection {

	@ConfDefault.DefaultString("&cUsage: /check <player>")
	Component usage();

	@ConfDefault.DefaultString("&cYou may not check players.")
	Component permission();

	@ConfDefault.DefaultString("&7Check for &e%TARGET%")
	ComponentText header();

	@ConfDefault.DefaultString("&7IP: &f%IP% &7— &f%COUNTRY%, %REGION%, %CITY%")
	ComponentText address();

	@ConfDefault.DefaultString("&7IP: &f%IP% &7— &cGeolocation unavailable")
	ComponentText addressUnavailable();

	@ConfDefault.DefaultString("&7IP: &funknown (no address recorded)")
	Component addressUnknown();

	@SubSection
	Punishment ban();

	@SubSection
	Punishment mute();

	@ConfDefault.DefaultString("&7Active warns: &f%COUNT%")
	ComponentText warns();
	interface Punishment {

		@ConfDefault.DefaultString("&7%LABEL%: &fnone")
		ComponentText none();

		@ConfDefault.DefaultString("&7%LABEL%: &f#%ID% — %TIME_REMAINING_SIMPLE% — %REASON%")
		ComponentText active();
	}
}
