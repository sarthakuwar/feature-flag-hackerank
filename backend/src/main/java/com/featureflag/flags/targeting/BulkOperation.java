package com.featureflag.flags.targeting;

/** How a bulk key change applies: ADD puts keys in, REMOVE takes them out. Both ignore keys that are already in the requested state. */
public enum BulkOperation {
	ADD,
	REMOVE
}
