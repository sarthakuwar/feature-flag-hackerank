package com.featureflag.flags.dto;

import java.util.List;

/**
 * restored: keys actually transitioned back to ACTIVE this call.
 * alreadyActive: idempotent no-ops - not archived, untouched (environments left exactly as they were).
 * notFound: keys that don't match any flag.
 */
public record BulkRestoreResult(List<String> restored, List<String> alreadyActive, List<String> notFound) {
}
