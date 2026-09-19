package com.featureflag.shared;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS is limited to the browser origins in CORS_ALLOWED_ORIGINS (comma separated); the default is the local dev UI. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final String[] allowedOrigins;

	public WebConfig(@Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://127.0.0.1:3000}") String allowedOrigins) {
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin -> !origin.isEmpty())
				.toArray(String[]::new);
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**").allowedOrigins(allowedOrigins)
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").allowedHeaders("Content-Type", "Accept");
	}
}
