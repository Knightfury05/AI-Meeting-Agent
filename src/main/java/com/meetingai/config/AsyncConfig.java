package com.meetingai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated thread pool for meeting processing (Whisper transcription +
 * Ollama summarization), kept separate from Tomcat's own request-handling
 * threads.
 *
 * Pool size is intentionally small: Whisper and Ollama are themselves
 * CPU/GPU-bound processes running on this same machine, so running many
 * meetings in parallel would make each one slower (they'd all compete for
 * the same CPU cores / GPU), not faster. 2 concurrent jobs is a reasonable
 * default for a single local machine — raise it only if you've confirmed
 * the hardware can actually run that many Whisper/Ollama processes at once
 * without thrashing.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "meetingProcessingExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("meeting-pipeline-");
        executor.initialize();
        return executor;
    }
}
