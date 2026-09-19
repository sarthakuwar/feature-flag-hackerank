package com.featureflag.flags.targeting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.mapping.Document;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.UserContext;

/**
 * A reusable group of contexts, scoped to one environment (the same key can mean a different
 * audience in staging and production). Membership precedence, first hit wins: explicitly included
 * keys, then explicitly excluded keys, then the rule-based conditions. Included/excluded are sets
 * so membership is O(1) however large a bulk import makes them.
 */
@Document("segments")
public class Segment implements Persistable<String> {

	@Id
	private String id;

	private EnvironmentKey environment;

	private String key;

	private String name;

	private String description;

	private String contextKind = UserContext.USER_KIND;

	private Set<String> included = new LinkedHashSet<>();

	private Set<String> excluded = new LinkedHashSet<>();

	private List<SegmentRule> rules = new ArrayList<>();

	private String updatedBy;

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

	public Segment() {
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

	public EnvironmentKey getEnvironment() {
		return environment;
	}

	public void setEnvironment(EnvironmentKey environment) {
		this.environment = environment;
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

	public String getContextKind() {
		return contextKind;
	}

	public void setContextKind(String contextKind) {
		this.contextKind = contextKind;
	}

	public Set<String> getIncluded() {
		return included;
	}

	public void setIncluded(Set<String> included) {
		this.included = included;
	}

	public Set<String> getExcluded() {
		return excluded;
	}

	public void setExcluded(Set<String> excluded) {
		this.excluded = excluded;
	}

	public List<SegmentRule> getRules() {
		return rules;
	}

	public void setRules(List<SegmentRule> rules) {
		this.rules = rules;
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
