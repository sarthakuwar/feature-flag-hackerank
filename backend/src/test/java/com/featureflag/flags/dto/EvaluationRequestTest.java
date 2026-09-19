package com.featureflag.flags.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.dto.EvaluationRequest.ContextRequest;
import com.featureflag.shared.ValidationException;

/** The size limits on an evaluation request: exactly at a limit passes, one over fails with a field error. */
class EvaluationRequestTest {

	private static String repeat(int length) {
		return "x".repeat(length);
	}

	private static Map<String, String> attributes(int count) {
		Map<String, String> attributes = new LinkedHashMap<>();

		for (int index = 0; index < count; index++) {
			attributes.put("attr-" + index, "value");
		}

		return attributes;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> fieldErrorsOf(EvaluationRequest request) {
		ValidationException failure = catchThrowableOfType(ValidationException.class, request::validate);

		assertThat(failure).isNotNull();

		assertThat(failure.getStatusCode()).isEqualTo(400);
		assertThat(failure.getCode()).isEqualTo("VALIDATION_ERROR");

		return (Map<String, List<String>>) failure.getDetails().get("fieldErrors");
	}

	private static EvaluationRequest withContext(String kind, ContextRequest context) {
		return new EvaluationRequest("user-1", Map.of(), Map.of(kind, context));
	}

	@Test
	void anEmptyRequestIsValid() {
		assertThatCode(() -> new EvaluationRequest(null, null).validate()).doesNotThrowAnyException();
		assertThatCode(() -> new EvaluationRequest(null, null, null).validate()).doesNotThrowAnyException();
	}

	@Test
	void aUserIdOfExactlyTheLimitPassesAndOneOverFails() {
		assertThatCode(() -> new EvaluationRequest(repeat(256), Map.of()).validate()).doesNotThrowAnyException();

		assertThat(fieldErrorsOf(new EvaluationRequest(repeat(257), Map.of()))).containsOnlyKeys("userId");
	}

	@Test
	void aContextKeyOfExactlyTheLimitPassesAndOneOverFails() {
		assertThatCode(() -> withContext("organization", new ContextRequest(repeat(256), Map.of())).validate())
				.doesNotThrowAnyException();

		assertThat(fieldErrorsOf(withContext("organization", new ContextRequest(repeat(257), Map.of()))))
				.containsOnlyKeys("contexts.organization.key");
	}

	@Test
	void aHundredAttributesPassAndAHundredAndOneFail() {
		assertThatCode(() -> new EvaluationRequest("u", attributes(100)).validate()).doesNotThrowAnyException();

		assertThat(fieldErrorsOf(new EvaluationRequest("u", attributes(101)))).containsOnlyKeys("attributes");
	}

	@Test
	void theAttributeLimitAppliesToEveryContextToo() {
		assertThatCode(() -> withContext("organization", new ContextRequest("acme", attributes(100))).validate())
				.doesNotThrowAnyException();

		assertThat(fieldErrorsOf(withContext("organization", new ContextRequest("acme", attributes(101)))))
				.containsOnlyKeys("contexts.organization.attributes");
	}

	@Test
	void anAttributeNameOfExactlyTheLimitPassesAndOneOverFails() {
		assertThatCode(() -> new EvaluationRequest("u", Map.of(repeat(128), "v")).validate()).doesNotThrowAnyException();

		Map<String, List<String>> errors = fieldErrorsOf(new EvaluationRequest("u", Map.of(repeat(129), "v")));

		assertThat(errors).containsOnlyKeys("attributes");
		assertThat(errors.get("attributes").get(0)).doesNotContain(repeat(129));
	}

	@Test
	void anAttributeValueOfExactlyTheLimitPassesAndOneOverFails() {
		assertThatCode(() -> new EvaluationRequest("u", Map.of("plan", repeat(1024))).validate()).doesNotThrowAnyException();

		assertThat(fieldErrorsOf(new EvaluationRequest("u", Map.of("plan", repeat(1025))))).containsOnlyKeys("attributes");
		assertThat(fieldErrorsOf(withContext("device", new ContextRequest("d", Map.of("os", repeat(1025))))))
				.containsOnlyKeys("contexts.device.attributes");
	}

	@Test
	void twentyContextsPassAndTwentyOneFail() {
		assertThatCode(() -> new EvaluationRequest("u", Map.of(), contexts(20)).validate()).doesNotThrowAnyException();

		assertThat(fieldErrorsOf(new EvaluationRequest("u", Map.of(), contexts(21)))).containsOnlyKeys("contexts");
	}

	@Test
	void aContextKindOfExactlyTheAttributeNameLimitPassesAndOneOverFails() {
		assertThatCode(() -> withContext(repeat(128), new ContextRequest("k", Map.of())).validate()).doesNotThrowAnyException();

		assertThat(fieldErrorsOf(withContext(repeat(129), new ContextRequest("k", Map.of())))).containsOnlyKeys("contexts");
	}

	@Test
	void everyViolationIsReportedTogether() {
		EvaluationRequest request = new EvaluationRequest(repeat(257), attributes(101),
				Map.of("organization", new ContextRequest(repeat(257), Map.of("plan", repeat(1025)))));

		assertThat(fieldErrorsOf(request)).containsOnlyKeys("userId", "attributes", "contexts.organization.key",
				"contexts.organization.attributes");
	}

	@Test
	void nullEntriesAreDroppedInsteadOfFailingTheRequest() {
		Map<String, String> withNullValue = new HashMap<>();
		withNullValue.put("plan", null);
		withNullValue.put("country", "US");

		EvaluationRequest request = new EvaluationRequest("u", withNullValue);

		assertThat(request.attributes()).containsOnlyKeys("country");
		assertThatCode(request::validate).doesNotThrowAnyException();
	}

	private static Map<String, ContextRequest> contexts(int count) {
		Map<String, ContextRequest> contexts = new LinkedHashMap<>();

		for (int index = 0; index < count; index++) {
			contexts.put("kind-" + index, new ContextRequest("key", Map.of()));
		}

		return contexts;
	}
}
