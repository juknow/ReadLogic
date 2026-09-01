package com.readlogic.backend.ocr.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.ocr", name = "enabled", havingValue = "true")
public class OcrWorkerConfiguration {

	@Bean(name = "ocrTaskExecutor")
	ThreadPoolTaskExecutor ocrTaskExecutor(OcrProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ocr-worker-");
		executor.setCorePoolSize(properties.concurrency());
		executor.setMaxPoolSize(properties.concurrency());
		executor.setQueueCapacity(0);
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.initialize();
		return executor;
	}
}
