package com.readlogic.backend.ocr.application;

public record OcrImage(
		byte[] content,
		String contentType,
		String fileName
) {
}
