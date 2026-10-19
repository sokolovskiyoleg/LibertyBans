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

package space.arim.libertybans.core.commands;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.kyori.adventure.text.ComponentLike;
import space.arim.api.jsonchat.adventure.util.ComponentText;
import space.arim.libertybans.api.NetworkAddress;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.api.select.PunishmentSelector;
import space.arim.libertybans.api.select.SelectionPredicate;
import space.arim.libertybans.core.alts.GeoLocation;
import space.arim.libertybans.core.commands.extra.AsCompositeWildcard;
import space.arim.libertybans.core.commands.extra.TabCompletion;
import space.arim.libertybans.core.config.CheckSection;
import space.arim.libertybans.core.config.InternalFormatter;
import space.arim.libertybans.core.env.CmdSender;
import space.arim.libertybans.core.env.Interlocutor;
import space.arim.libertybans.core.uuid.UUIDManager;
import space.arim.omnibus.util.concurrent.ReactionStage;

import java.util.UUID;
import java.util.Optional;
import java.util.stream.Stream;

import static space.arim.libertybans.api.select.SelectionPredicate.matchingAnyOf;

@Singleton
public final class CheckCommands extends AbstractSubCommandGroup {

	private final PunishmentSelector selector;
	private final InternalFormatter formatter;
	private final GeoLocation geoLocation;
	private final TabCompletion tabCompletion;
	private final Interlocutor interlocutor;
	private final UUIDManager uuidManager;

	@Inject
	public CheckCommands(Dependencies dependencies, PunishmentSelector selector, InternalFormatter formatter,
						 GeoLocation geoLocation, TabCompletion tabCompletion, Interlocutor interlocutor,
						 UUIDManager uuidManager) {
		super(dependencies, "check");
		this.selector = selector;
		this.formatter = formatter;
		this.geoLocation = geoLocation;
		this.tabCompletion = tabCompletion;
		this.interlocutor = interlocutor;
		this.uuidManager = uuidManager;
	}

	private CheckSection checkConfig() {
		return messages().check();
	}

	@Override
	public CommandExecution execute(CmdSender sender, CommandPackage command, String arg) {
		return new Execution(sender, command);
	}

	@Override
	public Stream<String> suggest(CmdSender sender, String arg, int argIndex) {
		return argIndex == 0 ? tabCompletion.completeOfflinePlayerNames(sender) : Stream.empty();
	}

	@Override
	public boolean hasTabCompletePermission(CmdSender sender, String arg) {
		return sender.hasPermission("libertybans.check");
	}

	private final class Execution extends AbstractCommandExecution {

		Execution(CmdSender sender, CommandPackage command) {
			super(sender, command);
		}

		@Override
		public ReactionStage<Void> execute() {
			if (!sender().hasPermission("libertybans.check")) {
				sender().sendMessage(checkConfig().permission());
				return null;
			}
			if (!command().hasNext()) {
				sender().sendMessage(checkConfig().usage());
				return null;
			}
			String target = command().next();
			return argumentParser().parseOrLookupUUID(sender(), target).thenCompose((uuid) -> {
				if (uuid == null) {
					return completedFuture(null);
				}
				return uuidManager.lookupLastAddress(uuid).thenCompose((address) ->
						activePunishment(uuid, address, PunishmentType.BAN).thenCompose((ban) ->
						activePunishment(uuid, address, PunishmentType.MUTE).thenCompose((mute) ->
							count(uuid, PunishmentType.WARN).thenCompose((warns) -> show(
								target, uuid, address, ban, mute, warns
							))
						))
					);
			});
		}

		private ReactionStage<Optional<Punishment>> activePunishment(UUID uuid, Optional<NetworkAddress> address,
																 PunishmentType type) {
			if (address.isPresent()) {
				return selector.getApplicablePunishment(uuid, address.get(), type);
			}
			return selector.selectionBuilder().type(type).victims(victims(uuid)).build()
					.getFirstSpecificPunishment();
		}

		private ReactionStage<Integer> count(UUID uuid, PunishmentType type) {
			return selector.selectionBuilder().type(type).victims(victims(uuid)).build().countNumberOfPunishments();
		}

		private SelectionPredicate<space.arim.libertybans.api.Victim> victims(UUID uuid) {
			return matchingAnyOf(
					PlayerVictim.of(uuid), new AsCompositeWildcard().apply(PlayerVictim.of(uuid))
			);
		}

		private ReactionStage<Void> show(String target, UUID uuid, Optional<NetworkAddress> address,
										 Optional<Punishment> ban,
										 Optional<Punishment> mute, int warns) {
			CheckSection conf = checkConfig();
			CmdSender receiver = sender();
		send(receiver, conf.header().replaceText("%TARGET%", target));
			send(receiver, conf.warns().replaceText("%COUNT%", Integer.toString(warns)));
			return showPunishment(conf.ban(), ban, "Ban").thenCompose((ignored) -> showPunishment(conf.mute(), mute, "Mute"))
					.thenCompose((ignored) -> {
						boolean canViewIp = receiver.hasPermission("libertybans.check.ip") &&
								(!interlocutor.shouldFilterIpAddresses() || receiver.hasPermission("libertybans.admin.viewips"));
						if (!canViewIp) {
							return completedFuture(null);
						}
						if (address.isEmpty()) {
							send(receiver, conf.addressUnknown());
							return completedFuture(null);
						}
						return futuresFactory().copyStage(geoLocation.lookup(address.get())).thenAccept((place) -> {
							ComponentText message = place == null ? conf.addressUnavailable() : conf.address();
							ComponentLike component = message.replaceText("%IP%", address.get().toString())
									.replaceText("%COUNTRY%", place == null ? "" : place.country())
									.replaceText("%REGION%", place == null ? "" : place.region())
									.replaceText("%CITY%", place == null ? "" : place.city());
							send(receiver, component);
						});
					});
		}

		private ReactionStage<Void> showPunishment(CheckSection.Punishment section, Optional<Punishment> punishment,
													 String label) {
			if (punishment.isEmpty()) {
				send(sender(), section.none().replaceText("%LABEL%", label));
				return completedFuture(null);
			}
			return formatter.formatWithPunishment(section.active().replaceText("%LABEL%", label), punishment.get())
					.thenAccept((message) -> send(sender(), message));
		}

		private void send(CmdSender receiver, ComponentLike message) {
			receiver.sendMessage(interlocutor.filterIpAddresses(receiver, message));
		}
	}
}
