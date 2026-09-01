package com.readlogic.backend.ocr.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("app.ocr")
public record OcrProperties(
		boolean enabled,
		@NotNull URI baseUrl,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout,
		@Min(1) int maxAttempts,
		@NotNull Duration pollInterval,
		@NotNull Duration staleAfter,
		@Min(1) @Max(32) int concurrency,
		@Min(1) @Max(100) int batchSize
) {
}
