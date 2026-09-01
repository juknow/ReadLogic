package com.readlogic.backend.book.api;

import com.readlogic.backend.common.error.InvalidRequestException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ImageUploadValidator {

	private final ImageUploadProperties properties;

	public ImageUploadValidator(ImageUploadProperties properties) {
		this.properties = properties;
	}

	public void validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidRequestException("빈 이미지는 등록할 수 없습니다.");
		}
		if (file.getSize() > properties.maxImageSizeBytes()) {
			throw new InvalidRequestException("이미지 한 장은 10MB를 초과할 수 없습니다.");
		}
		if (file.getContentType() == null || !properties.allowedContentTypes().contains(file.getContentType())) {
			throw new InvalidRequestException("지원하지 않는 이미지 형식입니다.");
		}
		String fileName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
		if (!StringUtils.hasText(fileName) || fileName.contains("..")) {
			throw new InvalidRequestException("이미지 파일명이 올바르지 않습니다.");
		}
	}
}

