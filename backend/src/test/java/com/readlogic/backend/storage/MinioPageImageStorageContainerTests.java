package com.readlogic.backend.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MinioPageImageStorageContainerTests {

	private static final String ACCESS_KEY = "readlogic-test";
	private static final String SECRET_KEY = "readlogic-test-password";

	@Container
	private static final GenericContainer<?> minio = new GenericContainer<>(
			DockerImageName.parse("minio/minio:RELEASE.2024-10-29T16-01-48Z")
	)
			.withEnv("MINIO_ROOT_USER", ACCESS_KEY)
			.withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
			.withCommand("server", "/data")
			.withExposedPorts(9000)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

	@Test
	void storesLoadsAndDeletesAnImage() {
		MinioProperties properties = new MinioProperties(
				"http://" + minio.getHost() + ":" + minio.getMappedPort(9000),
				ACCESS_KEY,
				SECRET_KEY,
				"readlogic-test-pages"
		);
		MinioClient client = MinioClient.builder()
				.endpoint(properties.endpoint())
				.credentials(properties.accessKey(), properties.secretKey())
				.build();
		MinioPageImageStorage storage = new MinioPageImageStorage(client, properties);
		byte[] content = new byte[]{1, 2, 3};

		String objectKey = storage.store(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"image/png",
				content.length,
				new ByteArrayInputStream(content)
		);

		PageImageStorage.StoredImage storedImage = storage.load(objectKey);
		assertThat(storedImage.content()).containsExactly(content);
		assertThat(storedImage.contentType()).isEqualTo("image/png");

		storage.delete(objectKey);
		assertThatThrownBy(() -> storage.load(objectKey)).isInstanceOf(ImageStorageException.class);
	}
}
