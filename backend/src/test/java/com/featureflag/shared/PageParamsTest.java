package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageParamsTest {

	@Test
	void missingOrBlankValuesFallBackToPageOneAndLimitFifty() {
		assertThat(PageParams.parse(null, null)).isEqualTo(new PageParams(1, 50));
		assertThat(PageParams.parse("", "  ")).isEqualTo(new PageParams(1, 50));
	}

	@Test
	void validValuesAreParsedIncludingTheLimitBounds() {
		assertThat(PageParams.parse("3", "10")).isEqualTo(new PageParams(3, 10));
		assertThat(PageParams.parse(" 2 ", "1")).isEqualTo(new PageParams(2, 1));
		assertThat(PageParams.parse("1", "200")).isEqualTo(new PageParams(1, 200));
	}

	@Test
	void everyInvalidValueIsReportedOnItsOwnField() {
		assertThat(fieldErrors("0", "50")).containsOnlyKeys("page");
		assertThat(fieldErrors("-1", "50")).containsOnlyKeys("page");
		assertThat(fieldErrors("abc", "50")).containsOnlyKeys("page");
		assertThat(fieldErrors("1.5", "50")).containsOnlyKeys("page");
		assertThat(fieldErrors("99999999999", "50")).containsOnlyKeys("page");
		assertThat(fieldErrors("1", "0")).containsOnlyKeys("limit");
		assertThat(fieldErrors("1", "201")).containsOnlyKeys("limit");
		assertThat(fieldErrors("1", "ten")).containsOnlyKeys("limit");
		assertThat(fieldErrors("0", "201")).containsOnlyKeys("page", "limit");
	}

	@Test
	void pageableIsZeroBasedAndCarriesTheSort() {
		Sort sort = Sort.by("name");

		Pageable pageable = new PageParams(3, 20).toPageable(sort);

		assertThat(pageable).isEqualTo(PageRequest.of(2, 20, sort));
	}

	@Test
	void metaReportsTheOneBasedPageTheLimitAndTheTotal() {
		PageImpl<String> page = new PageImpl<>(List.of("a"), PageRequest.of(1, 5), 6);

		assertThat(PageMeta.from(page)).isEqualTo(new PageMeta(2, 5, 6));
	}

	@Test
	void aPageBeyondTheLastKeepsTheRealTotal() {
		PageImpl<String> page = new PageImpl<>(List.of(), PageRequest.of(9, 5), 6);

		assertThat(PageMeta.from(page)).isEqualTo(new PageMeta(10, 5, 6));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> fieldErrors(String page, String limit) {
		ValidationException thrown = assertThrows(ValidationException.class, () -> PageParams.parse(page, limit));

		return (Map<String, List<String>>) thrown.getDetails().get("fieldErrors");
	}
}
