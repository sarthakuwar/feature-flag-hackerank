package com.featureflag.flags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("flags")
public class Flag implements Persistable<String> {

	@Id
	private String id;

	@Indexed(unique = true)
	private String key;

	private String name;

	private String description;

	private FlagType type;

	private FlagLifecycle lifecycle = FlagLifecycle.ACTIVE;

	private String owner;

	private List<Variation> variations = new ArrayList<>();

	private Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

	/** Other flag keys this flag needs to stay ACTIVE. Archiving a flag other active flags depend on is blocked. */
	private List<String> dependsOn = new ArrayList<>();

	/**
	 * Optimistic-lock counter: a save fails with {@code OptimisticLockingFailureException} when the
	 * stored document changed since it was read. Null on a document written before locking existed;
	 * {@link #isNew()} keys off the id, not this field, so such a document still updates in place.
	 */
	@Version
	private Long version;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public Flag() {
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return id == null;
	}

	public Long getVersion() {
		return version;
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

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public FlagType getType() {
		return type;
	}

	public void setType(FlagType type) {
		this.type = type;
	}

	public FlagLifecycle getLifecycle() {
		return lifecycle;
	}

	public void setLifecycle(FlagLifecycle lifecycle) {
		this.lifecycle = lifecycle;
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public List<Variation> getVariations() {
		return variations;
	}

	public void setVariations(List<Variation> variations) {
		this.variations = variations;
	}

	public Map<EnvironmentKey, EnvironmentConfig> getEnvironments() {
		return environments;
	}

	public void setEnvironments(Map<EnvironmentKey, EnvironmentConfig> environments) {
		this.environments = environments;
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

	public List<String> getDependsOn() {
		return dependsOn;
	}

	public void setDependsOn(List<String> dependsOn) {
		this.dependsOn = dependsOn;
	}

	public boolean hasVariation(String variationKey) {
		return variationKey != null && variations.stream().anyMatch(variation -> variation.getKey().equals(variationKey));
	}
}
