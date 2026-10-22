package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.service.AgentExecutionTaskQueue;
import com.kama.jchatmind.config.AsyncConfig;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;

public final class AgentExecutionTaskQueueWorkerProcessProbe {

    private AgentExecutionTaskQueueWorkerProcessProbe() {
    }

    private static void sleepInRunner(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Queue probe runner was interrupted", exception);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("JDBC URL, user, worker id and marker paths are required");
        }
        String jdbcUrl = args[0];
        String user = args[1];
        String workerId = args[2];
        Path readyFile = Path.of(args[3]);
        Path stopFile = Path.of(args[4]);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, user, "");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/AgentExecutionTaskMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentExecutionTaskQueue queue = new AgentExecutionTaskQueue(template.getMapper(AgentExecutionTaskMapper.class));
        AgentExecutionSessionLockManager lockManager = new AgentExecutionSessionLockManager(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Executor executor = new AsyncConfig().agentExecutionWorkerExecutor();
        AgentExecutionTaskRunner runner = event -> {
            jdbcTemplate.update("INSERT INTO g5_agent_queue_probe_execution "
                            + "(worker_id, session_id, sequence_no, started_at) "
                            + "VALUES (?, ?::uuid, ?, clock_timestamp())",
                    workerId, event.getSessionId(), event.getSequenceNo());
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Long distinctWorkers = jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT worker_id) FROM g5_agent_queue_probe_execution",
                        Long.class
                );
                if (distinctWorkers != null && distinctWorkers >= 2) {
                    break;
                }
                sleepInRunner(25);
            }
            Long distinctWorkers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT worker_id) FROM g5_agent_queue_probe_execution",
                    Long.class
            );
            if (distinctWorkers == null || distinctWorkers < 2) {
                throw new IllegalStateException("A second JVM worker did not enter the queue concurrently");
            }
            sleepInRunner(100);
            jdbcTemplate.update("UPDATE g5_agent_queue_probe_execution SET finished_at = clock_timestamp() "
                            + "WHERE worker_id = ? AND session_id = ?::uuid AND sequence_no = ?",
                    workerId, event.getSessionId(), event.getSequenceNo());
        };
        AgentExecutionTaskWorker worker = new AgentExecutionTaskWorker(
                queue,
                lockManager,
                runner,
                executor
        );
        Files.writeString(readyFile, Long.toString(ProcessHandle.current().pid()));
        System.out.println("G5_AGENT_QUEUE_WORKER_READY:" + workerId + ":" + ProcessHandle.current().pid());
        try {
            while (!Files.exists(stopFile)
                    || queue.countByStatus("PENDING") > 0
                    || queue.countByStatus("RUNNING") > 0) {
                worker.dispatchPendingTasks();
                Thread.sleep(25);
            }
            System.out.println("G5_AGENT_QUEUE_WORKER_STOP:" + workerId);
        } finally {
            if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool) {
                pool.shutdown();
            }
        }
    }
}
