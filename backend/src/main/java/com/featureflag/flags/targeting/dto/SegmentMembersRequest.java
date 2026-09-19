package com.featureflag.flags.targeting.dto;

import java.util.List;

import com.featureflag.flags.targeting.BulkOperation;
import com.featureflag.flags.targeting.MemberList;

/** ADD to INCLUDED also removes the key from EXCLUDED (and vice versa): a key is in at most one list. */
public record SegmentMembersRequest(MemberList list, BulkOperation operation, List<String> keys, String changedBy) {
}
