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

package space.arim.libertybans.core.alts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import space.arim.libertybans.api.NetworkAddress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Singleton
public final class GeoLocation {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.build();

	@Inject
	public GeoLocation() {}

	public record Place(String country, String region, String city) {}

	public CompletableFuture<Place> lookup(NetworkAddress address) {
		URI endpoint = URI.create("https://ipwho.is/" + address + "?fields=success,country,region,city");
		HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply((response) -> {
					if (response.statusCode() != 200) {
						return null;
					}
					JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
					if (!json.has("success") || !json.get("success").getAsBoolean()) {
						return null;
					}
					return new Place(value(json, "country"), value(json, "region"), value(json, "city"));
				})
				.exceptionally((ignored) -> null);
	}

	private static String value(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}

}
