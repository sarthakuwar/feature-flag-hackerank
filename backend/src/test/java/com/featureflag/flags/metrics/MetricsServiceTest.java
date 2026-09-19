package com.featureflag.flags.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.metrics.dto.ConversionRequest;
import com.featureflag.flags.metrics.dto.ExperimentMetricsResponse;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.ValidationException;

/** recordConversion's validation and persistence and metrics()'s assembly are covered here with mocks; the aggregations themselves run against a real MongoDB in MetricsControllerIT. */
@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

	@Mock
	private ImpressionRepository impressionRepository;

	@Mock
	private ConversionRepository conversionRepository;

	@Mock
	private ImpressionWriter impressionWriter;

	@Mock
	private FlagCacheService flagCacheService;

	private MetricsService metricsService;

	@BeforeEach
	void setUp() {
		metricsService = new MetricsService(impressionRepository, conversionRepository, impressionWriter, flagCacheService);
	}

	@Test
	void recordConversionRejectsMissingRequiredFields() {
		ConversionRequest request = new ConversionRequest(null, null, "a", null, null, null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ValidationException.class);
		verify(conversionRepository, never()).save(any());
	}

	@Test
	void recordConversionThrowsNotFoundForAnUnknownFlag() {
		when(flagCacheService.get("missing-flag")).thenReturn(Optional.empty());
		ConversionRequest request = new ConversionRequest("missing-flag", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase",
				null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "FLAG_NOT_FOUND");
	}

	@Test
	void recordConversionThrowsNotFoundForAnEnvironmentTheFlagDoesNotHave() {
		Flag flag = flagWithVariations("flag-a", "a", "b");
		flag.getEnvironments().remove(EnvironmentKey.STAGING);
		when(flagCacheService.get("flag-a")).thenReturn(Optional.of(flag));

		ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.STAGING, "a", "user-1", "purchase", null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "ENVIRONMENT_NOT_FOUND");
	}

	@Test
	void recordConversionThrowsNotFoundForAnUnknownVariation() {
		Flag flag = flagWithVariations("flag-a", "a", "b");
		when(flagCacheService.get("flag-a")).thenReturn(Optional.of(flag));

		ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "does-not-exist", "user-1",
				"purchase", null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "VARIATION_NOT_FOUND");
	}

	@Test
	void recordConversionPersistsAValidEvent() {
		Flag flag = flagWithVariations("flag-a", "a", "b");
		when(flagCacheService.get("flag-a")).thenReturn(Optional.of(flag));
		when(conversionRepository.save(any(ConversionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", 9.99);
		ConversionEvent saved = metricsService.recordConversion(request);

		assertThat(saved.getFlagKey()).isEqualTo("flag-a");
		assertThat(saved.getVariationKey()).isEqualTo("a");
		assertThat(saved.getEventType()).isEqualTo("purchase");
		assertThat(saved.getValue()).isEqualTo(9.99);
	}

	@Test
	void recordImpressionOnlyEnqueuesAndCarriesWhetherTheUserCountsTowardTheExperiment() {
		ArgumentCaptor<ImpressionEvent> captor = ArgumentCaptor.forClass(ImpressionEvent.class);

		metricsService.recordImpression("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", 5, EvaluationReason.INDIVIDUAL_TARGET, false);
		metricsService.recordImpression("flag-a", EnvironmentKey.PRODUCTION, "a", "user-2", 6, EvaluationReason.EXPERIMENT_ASSIGNED, true);

		verify(impressionWriter, times(2)).enqueue(captor.capture());
		verify(impressionRepository, never()).save(any(ImpressionEvent.class));
		assertThat(captor.getAllValues()).extracting(ImpressionEvent::isIncludedInExperiment).containsExactly(false, true);
		assertThat(captor.getAllValues()).extracting(ImpressionEvent::getUserId).containsExactly("user-1", "user-2");
	}

	@Test
	void recordConversionRequiresAVariationKeyAsAValidationErrorNotANotFound() {
		ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, null, "user-1", "purchase", null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ValidationException.class)
				.satisfies(exception -> assertThat(fieldErrors(exception)).containsOnlyKeys("variationKey"));
		verify(conversionRepository, never()).save(any());
	}

	@Test
	void recordConversionReportsEveryInvalidFieldAtOnce() {
		ConversionRequest request = new ConversionRequest("", null, " ", "u".repeat(257), "e".repeat(257), Double.NaN);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ValidationException.class)
				.satisfies(exception -> assertThat(fieldErrors(exception)).containsOnlyKeys("flagKey", "environment",
						"variationKey", "userId", "eventType", "value"));
	}

	@Test
	void recordConversionRejectsBlankIdentifiers() {
		ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "  ", "", null);

		assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ValidationException.class)
				.satisfies(exception -> assertThat(fieldErrors(exception)).containsOnlyKeys("userId", "eventType"));
	}

	@Test
	void recordConversionRejectsAValueThatIsNotFiniteOrIsOutOfBounds() {
		for (Double value : new Double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e13, -1e13 }) {
			ConversionRequest request = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", value);

			assertThatThrownBy(() -> metricsService.recordConversion(request)).isInstanceOf(ValidationException.class)
					.satisfies(exception -> assertThat(fieldErrors(exception)).containsOnlyKeys("value"));
		}

		verify(conversionRepository, never()).save(any());
	}

	@Test
	void recordConversionAcceptsTheLargestAllowedFieldsAndValues() {
		stubValidFlag();

		ConversionEvent saved = metricsService.recordConversion(new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a",
				"u".repeat(256), "e".repeat(256), -1e12));

		assertThat(saved.getUserId()).hasSize(256);
		assertThat(saved.getValue()).isEqualTo(-1e12);
	}

	@Test
	void aConversionFlushesQueuedImpressionsBeforeLookingUpTheUsersLatestOne() {
		stubValidFlag();

		metricsService.recordConversion(new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", null));

		InOrder order = inOrder(impressionWriter, impressionRepository);
		order.verify(impressionWriter).flush();
		order.verify(impressionRepository).findFirstByFlagKeyAndEnvironmentAndUserIdOrderByOccurredAtDesc("flag-a",
				EnvironmentKey.PRODUCTION, "user-1");
	}

	@Test
	void metricsFlushesQueuedImpressionsAndCombinesTheRepositoryCounts() {
		when(flagCacheService.get("flag-a")).thenReturn(Optional.of(flagWithVariations("flag-a", "a", "b")));
		when(impressionRepository.countIncludedByVariation("flag-a", EnvironmentKey.PRODUCTION))
				.thenReturn(List.of(new VariationCount("a", 10), new VariationCount("b", 4)));
		when(conversionRepository.countIncludedByVariation("flag-a", EnvironmentKey.PRODUCTION, "purchase"))
				.thenReturn(List.of(new VariationCount("a", 5)));
		when(impressionRepository.countExcluded("flag-a", EnvironmentKey.PRODUCTION)).thenReturn(3L);

		ExperimentMetricsResponse response = metricsService.metrics("flag-a", EnvironmentKey.PRODUCTION, "purchase");

		verify(impressionWriter).flush();
		assertThat(response.excludedImpressions()).isEqualTo(3);
		assertThat(response.banditAlgorithm()).isEqualTo(BanditAlgorithm.NONE);
		assertThat(response.variations()).extracting("variationKey", "impressions", "conversions", "conversionRate")
				.containsExactly(tuple("a", 10L, 5L, 0.5),
						tuple("b", 4L, 0L, 0.0));
	}

	@Test
	void aConversionFromAUserWhoseLatestImpressionWasExcludedIsExcludedToo() {
		stubValidFlag();
		when(impressionRepository.findFirstByFlagKeyAndEnvironmentAndUserIdOrderByOccurredAtDesc("flag-a", EnvironmentKey.PRODUCTION, "vip"))
				.thenReturn(Optional.of(new ImpressionEvent("flag-a", EnvironmentKey.PRODUCTION, "a", "vip", 1,
						EvaluationReason.INDIVIDUAL_TARGET, false)));

		ConversionEvent saved = metricsService.recordConversion(
				new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "vip", "purchase", null));

		assertThat(saved.isIncludedInExperiment()).isFalse();
	}

	@Test
	void aConversionFromAUserWhoseLatestImpressionWasIncludedIsIncluded() {
		stubValidFlag();
		when(impressionRepository.findFirstByFlagKeyAndEnvironmentAndUserIdOrderByOccurredAtDesc("flag-a", EnvironmentKey.PRODUCTION, "organic"))
				.thenReturn(Optional.of(new ImpressionEvent("flag-a", EnvironmentKey.PRODUCTION, "a", "organic", 1,
						EvaluationReason.EXPERIMENT_ASSIGNED, true)));

		ConversionEvent saved = metricsService.recordConversion(
				new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "organic", "purchase", null));

		assertThat(saved.isIncludedInExperiment()).isTrue();
	}

	@Test
	void aConversionFromAUserWithNoImpressionYetStillCountsAsBeforeTargetingExisted() {
		stubValidFlag();

		ConversionEvent saved = metricsService.recordConversion(
				new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "unseen", "purchase", null));

		assertThat(saved.isIncludedInExperiment()).isTrue();
	}

	private static Map<String, Object> fieldErrors(Throwable exception) {
		return (Map<String, Object>) ((ApiException) exception).getDetails().get("fieldErrors");
	}

	private void stubValidFlag() {
		when(flagCacheService.get("flag-a")).thenReturn(Optional.of(flagWithVariations("flag-a", "a", "b")));
		when(conversionRepository.save(any(ConversionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Flag flagWithVariations(String key, String... variationKeys) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName(key);
		flag.setType(FlagType.MULTIVARIATE);
		flag.setLifecycle(FlagLifecycle.ACTIVE);

		List<Variation> variations = new ArrayList<>();

		for (String variationKey : variationKeys) {
			variations.add(new Variation(variationKey, variationKey, variationKey));
		}

		flag.setVariations(variations);

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(true, variationKeys[0], new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}
}
