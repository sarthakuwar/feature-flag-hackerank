package com.featureflag.flags.dto;

import java.util.List;
import java.util.Map;

/**
 * archived: keys actually transitioned to ARCHIVED this call.
 * alreadyArchived: idempotent no-ops - already archived, untouched.
 * blocked: keys not archived because another still-active flag depends on them, mapped to the blocking dependents.
 * notFound: keys that don't match any flag.
 */
public record BulkArchiveResult(List<String> archived, List<String> alreadyArchived, Map<String, List<String>> blocked,
		List<String> notFound) {
}
