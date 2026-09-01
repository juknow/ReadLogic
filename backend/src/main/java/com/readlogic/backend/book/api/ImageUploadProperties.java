package com.readlogic.backend.book.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.upload")
public record ImageUploadProperties(
		@Positive long maxImageSizeBytes,
		@NotEmpty List<String> allowedContentTypes
) {
}

