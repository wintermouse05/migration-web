package org.example.migrationdbweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Cấu hình Async cho Spring Boot.
 *
 * Mặc định Spring Boot dùng SimpleAsyncTaskExecutor — KHÔNG giới hạn số thread.
 * Nếu frontend spam nhiều request cùng lúc, mỗi request tạo thread riêng,
 * mỗi thread tạo 2 HikariCP pool → connection exhaustion.
 *
 * Cấu hình ThreadPoolTaskExecutor với:
 * - corePoolSize = 2  : luôn có 2 thread sẵn sàng
 * - maxPoolSize = 4   : tối đa 4 thread đồng thời (giới hạn số migration song song)
 * - queueCapacity = 10: queue cho 10 job chờ, vượt quá → Reject
 * - threadNamePrefix: prefix cho log dễ debug
 * - rejectionPolicy = CallerRunsPolicy: nếu queue đầy, caller thread chạy trực tiếp
 *                       (thay vì throw RejectedExecutionException)
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("MigrationAsync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // CallerRunsPolicy: nếu queue đầy, chạy trực tiếp trên thread gọi
        // thay vì reject. Tránh mất job nhưng có thể block frontend request.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}
