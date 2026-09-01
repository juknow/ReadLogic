package com.readlogic.backend.common.error;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
		String code,
		String message,
		Map<String, String> fieldErrors,
		Instant timestamp
) {
	public static ApiErrorResponse of(String code, String message) {
		return new ApiErrorResponse(code, message, Map.of(), Instant.now());
	}

	public static ApiErrorResponse withFields(String code, String message, Map<String, String> fieldErrors) {
		return new ApiErrorResponse(code, message, fieldErrors, Instant.now());
	}
}

