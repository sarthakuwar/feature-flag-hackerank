package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;

import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.targeting.TargetingCacheService;

class HealthControllerTest {

	private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

	private final FlagCacheService flagCacheService = mock(FlagCacheService.class);

	private final TargetingCacheService targetingCacheService = mock(TargetingCacheService.class);

	private final HealthController controller = new HealthController(mongoTemplate, flagCacheService, targetingCacheService);

	@Test
	void healthReportsAConnectedDatabase() {
		ResponseEntity<Map<String, Object>> response = controller.health();

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(data(response)).containsEntry("status", "ok").containsEntry("database", "connected");
	}

	@Test
	void healthIsDegradedWith503WhenMongoCannotBePinged() {
		mongoDown();

		ResponseEntity<Map<String, Object>> response = controller.health();

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(data(response)).containsEntry("status", "degraded").containsEntry("database", "disconnected");
	}

	@Test
	void liveAlwaysAnswers200WithoutTouchingAnyDependency() {
		mongoDown();

		ResponseEntity<Map<String, Object>> response = controller.live();

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(data(response)).containsEntry("status", "ok");
		verifyNoInteractions(mongoTemplate, flagCacheService, targetingCacheService);
	}

	@Test
	void readyWhenMongoRespondsAndBothCachesAreLoaded() {
		cachesLoaded(true, true);

		ResponseEntity<Map<String, Object>> response = controller.ready();

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(data(response)).containsEntry("status", "ready").containsEntry("database", "connected")
				.containsEntry("caches", "loaded");
	}

	@Test
	void notReadyWith503WhenMongoIsDown() {
		cachesLoaded(true, true);
		mongoDown();

		ResponseEntity<Map<String, Object>> response = controller.ready();

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(data(response)).containsEntry("status", "not_ready").containsEntry("database", "disconnected");
	}

	@Test
	void notReadyWith503UntilBothCachesAreLoaded() {
		cachesLoaded(false, true);
		assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);

		cachesLoaded(true, false);
		ResponseEntity<Map<String, Object>> response = controller.ready();

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(data(response)).containsEntry("caches", "loading").containsEntry("database", "connected");
	}

	private void mongoDown() {
		when(mongoTemplate.executeCommand(any(Document.class))).thenThrow(new DataAccessResourceFailureException("down"));
	}

	private void cachesLoaded(boolean flags, boolean targeting) {
		when(flagCacheService.isLoaded()).thenReturn(flags);
		when(targetingCacheService.isLoaded()).thenReturn(targeting);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}
}
