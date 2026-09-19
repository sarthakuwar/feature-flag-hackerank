package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.client.MongoClient;
import com.mongodb.client.internal.MongoClientImpl;

/** Cross-cutting HTTP behavior: health probes, response headers, CORS and the request body cap. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperationsIT {

	private static final String UI_ORIGIN = "http://localhost:3000";

	private static final int ONE_MEGABYTE = 1_048_576;

	private final HttpClient client = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private MongoClient mongoClient;

	@Test
	void healthLiveAndReadyAnswer200WithTheDataEnvelope() throws Exception {
		HttpResponse<String> health = send(request("/api/v1/health").GET());
		HttpResponse<String> live = send(request("/api/v1/health/live").GET());
		HttpResponse<String> ready = send(request("/api/v1/health/ready").GET());

		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(health.body()).contains("\"status\":\"ok\"").contains("\"database\":\"connected\"");
		assertThat(live.statusCode()).isEqualTo(200);
		assertThat(live.body()).isEqualTo("{\"data\":{\"status\":\"ok\"}}");
		assertThat(ready.statusCode()).isEqualTo(200);
		assertThat(ready.body()).contains("\"status\":\"ready\"").contains("\"caches\":\"loaded\"");
	}

	@Test
	void everyResponseCarriesTheSecurityHeaders() throws Exception {
		HttpResponse<String> ok = send(request("/api/v1/health/live").GET());
		HttpResponse<String> missing = send(request("/api/v1/no-such-route").GET());
		HttpResponse<String> tooLarge = send(request("/api/v1/flags").POST(BodyPublishers.ofByteArray(new byte[ONE_MEGABYTE + 1])));

		for (HttpResponse<String> response : List.of(ok, missing, tooLarge)) {
			assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
			assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
		}
	}

	@Test
	void corsAllowsTheLocalUiOriginAndNoOthers() throws Exception {
		HttpResponse<String> allowed = send(preflight(UI_ORIGIN, "POST", "Content-Type"));
		HttpResponse<String> foreign = send(preflight("https://evil.example", "POST", "Content-Type"));

		assertThat(allowed.statusCode()).isEqualTo(200);
		assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(UI_ORIGIN);
		assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PATCH", "DELETE")
				.doesNotContain("TRACE");
		assertThat(foreign.statusCode()).isEqualTo(403);
		assertThat(foreign.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
	}

	@Test
	void corsRefusesRequestHeadersOtherThanContentTypeAndAccept() throws Exception {
		HttpResponse<String> response = send(preflight(UI_ORIGIN, "POST", "X-Custom-Header"));

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	void aBodyDeclaredOverTheLimitIsRefusedWith413AndTheErrorEnvelope() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/flags").header("Content-Type", "application/json")
				.POST(BodyPublishers.ofByteArray(new byte[ONE_MEGABYTE + 1])));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
	}

	@Test
	void aChunkedBodyOverTheLimitIsRefusedToo() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/flags").header("Content-Type", "application/json")
				.POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[ONE_MEGABYTE + 1]))));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
	}

	@Test
	void aSmallChunkedBodyReachesTheControllerIntact() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/flags").header("Content-Type", "application/json")
				.POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("{}".getBytes()))));

		assertThat(response.statusCode()).as("the empty object was parsed and then failed validation").isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"VALIDATION_ERROR\"");
	}

	@Test
	void aBodyExactlyAtTheLimitIsNotRefusedForItsSize() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/flags").header("Content-Type", "application/json")
				.POST(BodyPublishers.ofByteArray(new byte[ONE_MEGABYTE])));

		assertThat(response.statusCode()).isNotEqualTo(413);
	}

	@Test
	void theRunningMongoClientUsesTheBoundedTimeouts() {
		var settings = ((MongoClientImpl) mongoClient).getSettings();

		assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
		assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
		assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(30_000);
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
	}

	private HttpRequest.Builder preflight(String origin, String method, String headers) {
		return request("/api/v1/flags").method("OPTIONS", BodyPublishers.noBody()).header("Origin", origin)
				.header("Access-Control-Request-Method", method).header("Access-Control-Request-Headers", headers);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		return client.send(builder.build(), BodyHandlers.ofString());
	}
}
