package com.readlogic.backend.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationContainerTests {

	@Container
	private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Test
	void appliesBookSchemaToPostgres() throws Exception {
		Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();

		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.prepareStatement("""
					 SELECT COUNT(*)
					 FROM information_schema.tables
					 WHERE table_schema = 'public' AND table_name IN ('books', 'book_pages')
					 """)) {
			try (var result = statement.executeQuery()) {
				result.next();
				assertThat(result.getInt(1)).isEqualTo(2);
			}
		}

		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.prepareStatement("""
					 SELECT COUNT(*)
					 FROM information_schema.columns
					 WHERE table_schema = 'public'
					   AND table_name = 'book_pages'
					   AND column_name IN (
					       'ocr_revision', 'ocr_attempt_count', 'ocr_confidence', 'ocr_engine',
					       'ocr_model', 'ocr_last_error_code', 'ocr_last_error_message',
					       'ocr_requested_at', 'ocr_started_at', 'ocr_completed_at',
					       'ocr_next_attempt_at', 'text_source'
					   )
					 """)) {
			try (var result = statement.executeQuery()) {
				result.next();
				assertThat(result.getInt(1)).isEqualTo(12);
			}
		}
	}
}

