package org.buaa.rag.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RetrievalExecutorConfiguration {

    @Bean("retrievalChannelExecutor")
    public Executor retrievalChannelExecutor() {
        return buildExecutor(4, 8, 200, "retrieval-channel-");
    }

    @Bean("memorySummaryExecutor")
    public Executor memorySummaryExecutor() {
        return buildExecutor(1, 2, 100, "memory-summary-");
    }

    @Bean("intentResolutionExecutor")
    public Executor intentResolutionExecutor() {
        return buildExecutor(4, 8, 200, "intent-resolution-");
    }

    @Bean("chatStreamExecutor")
    public Executor chatStreamExecutor() {
        return buildExecutor(4, 16, 500, "chat-stream-");
    }

    @Bean("subQueryContextExecutor")
    public Executor subQueryContextExecutor() {
        return buildExecutor(4, 12, 300, "subquery-ctx-");
    }

    private Executor buildExecutor(int core, int max, int queueCapacity, String namePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(namePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
