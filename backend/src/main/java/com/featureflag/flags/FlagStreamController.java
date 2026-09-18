package com.featureflag.flags;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The in-app substitute for "streaming flag updates to SDK instances." WebSockets are unavailable
 * in this runtime, so this is a plain Server-Sent Events endpoint (no new dependency).
 */
@RestController
@RequestMapping("/api/v1/stream")
public class FlagStreamController {

	private final FlagStreamService flagStreamService;

	public FlagStreamController(FlagStreamService flagStreamService) {
		this.flagStreamService = flagStreamService;
	}

	@GetMapping(value = "/{environment}", produces = "text/event-stream")
	public SseEmitter subscribe(@PathVariable EnvironmentKey environment) {
		return flagStreamService.subscribe(environment);
	}
}
