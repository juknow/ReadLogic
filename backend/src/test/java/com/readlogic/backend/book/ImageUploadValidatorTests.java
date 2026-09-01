package com.readlogic.backend.book;

import com.readlogic.backend.book.api.ImageUploadProperties;
import com.readlogic.backend.book.api.ImageUploadValidator;
import com.readlogic.backend.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUploadValidatorTests {

	private final ImageUploadValidator validator = new ImageUploadValidator(
			new ImageUploadProperties(3, List.of("image/png"))
	);

	@Test
	void acceptsConfiguredImageTypeWithinSizeLimit() {
		MockMultipartFile image = new MockMultipartFile("image", "page.png", "image/png", new byte[]{1, 2, 3});

		assertThatCode(() -> validator.validate(image)).doesNotThrowAnyException();
	}

	@Test
	void rejectsUnsupportedOrOversizedImages() {
		MockMultipartFile unsupported = new MockMultipartFile("image", "page.gif", "image/gif", new byte[]{1});
		MockMultipartFile oversized = new MockMultipartFile("image", "page.png", "image/png", new byte[]{1, 2, 3, 4});

		assertThatThrownBy(() -> validator.validate(unsupported)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> validator.validate(oversized)).isInstanceOf(InvalidRequestException.class);
	}
}

