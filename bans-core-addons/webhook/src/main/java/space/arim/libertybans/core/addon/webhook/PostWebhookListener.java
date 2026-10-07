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

package space.arim.libertybans.core.addon.webhook;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.arim.api.jsonchat.adventure.util.Adventure5Compat;
import space.arim.api.jsonchat.adventure.util.ComponentText;
import space.arim.libertybans.api.AddressVictim;
import space.arim.libertybans.api.CompositeVictim;
import space.arim.libertybans.api.Operator;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.event.PostOpNotificationEvent;
import space.arim.libertybans.api.event.PostPardonEvent;
import space.arim.libertybans.api.event.PostPunishEvent;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.core.config.InternalFormatter;
import space.arim.libertybans.core.database.DatabaseManager;
import space.arim.libertybans.core.service.FuturePoster;
import space.arim.omnibus.events.ListenerPriorities;
import space.arim.omnibus.events.ListeningMethod;
import space.arim.omnibus.util.ThisClass;
import space.arim.omnibus.util.concurrent.CentralisedFuture;
import space.arim.omnibus.util.concurrent.FactoryOfTheFuture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Singleton
public final class PostWebhookListener {

    private final WebhookAddon addon;
    private final InternalFormatter formatter;
    private final FuturePoster futurePoster;
    private final Adventure5Compat adventure5Compat;
    private final DatabaseManager databaseManager;
    private final FactoryOfTheFuture futuresFactory;
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Logger logger = LoggerFactory.getLogger(ThisClass.get());

    @Inject
    public PostWebhookListener(WebhookAddon addon, InternalFormatter formatter, FuturePoster futurePoster,
                               Adventure5Compat adventure5Compat, DatabaseManager databaseManager,
                               FactoryOfTheFuture futuresFactory) {
        this.addon = addon;
        this.formatter = formatter;
        this.futurePoster = futurePoster;
        this.adventure5Compat = adventure5Compat;
        this.databaseManager = databaseManager;
        this.futuresFactory = futuresFactory;
    }

    @ListeningMethod(priority = ListenerPriorities.LOW)
    public void onPunish(PostPunishEvent event) {
        onEvent(event, null, WebhookConfig::onPunish);
    }

    @ListeningMethod(priority = ListenerPriorities.LOW)
    public void onPardon(PostPardonEvent event) {
        Operator unOperator = event.getOperator();
        Objects.requireNonNull(unOperator, "unOperator"); // bad event impl
        onEvent(event, unOperator, WebhookConfig::onPardon);
    }

    private void onEvent(PostOpNotificationEvent event, @Nullable Operator unOperator,
                         Function<WebhookConfig, WebhookConfig.EventPayload> getEventPayload) {
        WebhookConfig config = addon.config();
        if (!config.enable()) {
            return;
        }
        URI webhookUrl = config.webhookUrl();
        WebhookConfig.EventPayload eventPayload = getEventPayload.apply(config);
        if (!eventPayload.enable()) {
            return;
        }
        String jsonPayload = eventPayload.jsonPayload();
        {
            String target = event.getTarget().orElse(null);
            jsonPayload = jsonPayload.replace("%TARGET%", target == null ? "<none>" : target);
        }
        // Resolve victim display (nickname) up-front so %VICTIM_NAME% works even when
        // %VICTIM% is just an IP (e.g. an AddressVictim with no associated name).
        String victimName = null;
        {
            Victim victim = event.getPunishment().getVictim();
            CentralisedFuture<String> victimNameFuture;
            if (victim instanceof PlayerVictim playerVictim) {
                victimNameFuture = formatter.formatVictim(playerVictim);
            } else if (victim instanceof CompositeVictim compositeVictim) {
                // LOOKUP is a complete future every time we call this ourselves,
                // because of UUIDManager's fastCache.
                victimNameFuture = formatter.formatVictim(compositeVictim);
            } else if (victim instanceof AddressVictim addressVictim) {
                // For IP-only bans (e.g. /ipban), the AddressVictim carries no UUID,
                // so formatVictim has nothing to resolve. Look up the player that owns
                // this IP in the names table instead, and use their last known name.
                victimNameFuture = lookupNameByAddress(addressVictim);
            } else {
                victimNameFuture = null;
            }
            if (victimNameFuture != null) {
                victimName = victimNameFuture.join();
            }
        }
        jsonPayload = jsonPayload.replace("%VICTIM_NAME%", victimName == null ? "" : victimName);
        ComponentText formattable = ComponentText.create(Component.text(jsonPayload), adventure5Compat);
        CompletableFuture<Component> formatted;
        {
            Punishment punishment = event.getPunishment();
            boolean silent = event.isSilent();
            if (unOperator == null) {
                formatted = formatter.formatNotificationIssue(formattable, punishment, silent);
            } else {
                formatted = formatter.formatNotificationRevoke(formattable, punishment, unOperator, silent);
            }
        }
        var future = formatted.thenCompose(component -> {
            String json = ((TextComponent) component).content();
            return postWebhook(webhookUrl, json);
        });
        futurePoster.postFuture(future);
    }

    private CompletableFuture<Void> postWebhook(URI webhookUrl, String json) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(webhookUrl)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(40L))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
            int statusCode = response.statusCode();
            if (statusCode == 200 || statusCode == 204) {
                logger.debug("Successfully posted webhook");
            } else {
                logger.warn("Received unexpected status code {} from webhook API", statusCode);
            }
        });
    }

    /**
     * Resolves the most-recent player name associated with the given IP address by
     * querying the LibertyBans {@code addresses} / {@code names} tables directly.
     *
     * <p>This is used to enrich {@code %VICTIM_NAME%} when the victim is an
     * {@link AddressVictim} (e.g. an {@code /ipban}) and so has no nickname attached
     * to the punishment itself.
     *
     * @return a future yielding the player name, or an empty string if no player is
     *         associated with this address
     */
    private CentralisedFuture<String> lookupNameByAddress(AddressVictim addressVictim) {
        byte[] rawAddress = addressVictim.getAddress().getRawAddress();
        String name;
        try (Connection conn = databaseManager.getInternal().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                             "SELECT n.name FROM libertybans_addresses a " +
                             "JOIN libertybans_names n ON n.uuid = a.uuid " +
                             "WHERE a.address = ? " +
                             "ORDER BY a.updated DESC LIMIT 1")) {
            ps.setBytes(1, rawAddress);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString(1);
                } else {
                    name = null;
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to resolve player name by IP for webhook", ex);
            return futuresFactory.completedFuture(null);
        }
        return futuresFactory.completedFuture(name);
    }
}
