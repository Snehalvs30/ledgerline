package com.ledgerline;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka for tests, started in Docker by Testcontainers and
 * thrown away afterwards. @ServiceConnection points Spring at them automatically,
 * so tests never depend on whatever is running on your laptop.
 *
 * Containers are Spring beans, so they start once and are shared by every test
 * class that uses the same configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Same major version as compose.yaml, so tests match local development.
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
	}
}