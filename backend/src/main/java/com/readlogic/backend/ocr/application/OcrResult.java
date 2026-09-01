package com.readlogic.backend.ocr.application;

import java.math.BigDecimal;

public record OcrResult(
		String text,
		BigDecimal confidence,
		String engine,
		String model
) {
}
