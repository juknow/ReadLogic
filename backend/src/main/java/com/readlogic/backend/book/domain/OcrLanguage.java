package com.readlogic.backend.book.domain;

import java.util.Locale;

public enum OcrLanguage {
	AUTO,
	KO,
	EN,
	JA,
	ZH;

	public static OcrLanguage fromApiValue(String value) {
		if (value == null || value.isBlank()) {
			return KO;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("OCR 언어는 auto, ko, en, ja, zh 중 하나여야 합니다.", exception);
		}
	}

	public String apiValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}
