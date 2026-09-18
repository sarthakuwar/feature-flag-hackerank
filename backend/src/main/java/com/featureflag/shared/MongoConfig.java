package com.featureflag.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

	public MongoConfig(MappingMongoConverter converter) {
		converter.setTypeMapper(new DefaultMongoTypeMapper(null));
	}
}
