package com.featureflag.shared;

import org.springframework.data.domain.Page;

/** The {@code meta} sibling of {@code data} in a paged response: the 1-based page, the page size, and the total match count. */
public record PageMeta(int page, int limit, long total) {

	public static PageMeta from(Page<?> page) {
		return new PageMeta(page.getNumber() + 1, page.getSize(), page.getTotalElements());
	}
}
