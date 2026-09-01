package com.readlogic.backend.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

@Component
public class MinioPageImageStorage implements PageImageStorage {

	private final MinioClient minioClient;
	private final String bucket;
	private volatile boolean bucketReady;

	public MinioPageImageStorage(MinioClient minioClient, MinioProperties properties) {
		this.minioClient = minioClient;
		this.bucket = properties.bucket();
	}

	@Override
	public String store(UUID bookId, UUID pageId, String contentType, long size, InputStream content) {
		String objectKey = "books/%s/pages/%s/%s".formatted(bookId, pageId, UUID.randomUUID());
		try {
			ensureBucket();
			minioClient.putObject(PutObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.contentType(contentType)
					.stream(content, size, -1)
					.build());
			return objectKey;
		} catch (Exception exception) {
			throw new ImageStorageException("페이지 이미지를 저장하지 못했습니다.", exception);
		}
	}

	@Override
	public StoredImage load(String objectKey) {
		try {
			ensureBucket();
			StatObjectResponse metadata = minioClient.statObject(StatObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.build());
			try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.build())) {
				return new StoredImage(response.readAllBytes(), metadata.contentType());
			}
		} catch (Exception exception) {
			throw new ImageStorageException("페이지 이미지를 불러오지 못했습니다.", exception);
		}
	}

	@Override
	public void delete(String objectKey) {
		try {
			ensureBucket();
			minioClient.removeObject(RemoveObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.build());
		} catch (Exception exception) {
			throw new ImageStorageException("페이지 이미지를 삭제하지 못했습니다.", exception);
		}
	}

	private synchronized void ensureBucket() throws Exception {
		if (bucketReady) {
			return;
		}
		boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
		if (!exists) {
			minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
		}
		bucketReady = true;
	}
}
