package com.readlogic.backend.storage;

import java.io.InputStream;
import java.util.UUID;

public interface PageImageStorage {

	String store(UUID bookId, UUID pageId, String contentType, long size, InputStream content);

	StoredImage load(String objectKey);

	void delete(String objectKey);

	record StoredImage(byte[] content, String contentType) {
	}
}

