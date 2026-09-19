package com.featureflag.flags.targeting;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A mobile application known to the flag service. {@code key} is the application id the mobile SDK
 * reports as the key of its automatically collected ld_application context; a version at or above
 * {@code minSupportedVersion} counts as "supported" for {@link ClauseOperator#APP_VERSION_SUPPORTED}
 * clauses. Global rather than per-environment: an app's support policy doesn't differ by environment.
 */
@Document("mobile_applications")
public class MobileApplication {

	@Id
	private String id;

	private String key;

	private String name;

	private String minSupportedVersion;

	private String updatedBy;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public MobileApplication() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMinSupportedVersion() {
		return minSupportedVersion;
	}

	public void setMinSupportedVersion(String minSupportedVersion) {
		this.minSupportedVersion = minSupportedVersion;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
