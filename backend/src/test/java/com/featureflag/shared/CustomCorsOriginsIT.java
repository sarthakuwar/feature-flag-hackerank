package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** CORS_ALLOWED_ORIGINS replaces the default origins; whitespace around entries is ignored. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "CORS_ALLOWED_ORIGINS=https://ui.example.com , https://admin.example.com")
@ActiveProfiles("test")
class CustomCorsOriginsIT {

	@LocalServerPort
	private int port;

	@Test
	void onlyTheConfiguredOriginsAreAllowed() throws Exception {
		assertThat(preflightStatus("https://ui.example.com")).isEqualTo(200);
		assertThat(preflightStatus("https://admin.example.com")).isEqualTo(200);
		assertThat(preflightStatus("http://localhost:3000")).as("the default is replaced, not extended").isEqualTo(403);
	}

	private int preflightStatus(String origin) throws Exception {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/flags"))
				.method("OPTIONS", BodyPublishers.noBody()).header("Origin", origin).header("Access-Control-Request-Method", "GET")
				.build();

		HttpResponse<String> response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

		return response.statusCode();
	}
}
