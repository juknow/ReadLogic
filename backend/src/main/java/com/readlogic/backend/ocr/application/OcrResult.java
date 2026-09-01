package com.readlogic.backend.ocr.application;

import java.math.BigDecimal;
import java.util.Map;

public record OcrResult(
		String text,
		BigDecimal confidence,
		String engine,
		String model,
		Map<String, Object> document
) {
	public OcrResult(String text, BigDecimal confidence, String engine, String model) {
		this(text, confidence, engine, model, null);
	}
}
