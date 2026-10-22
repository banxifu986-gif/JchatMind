package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.config.AsyncConfig;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.AgentExecutionTask;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.service.AgentExecutionTaskQueue;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "g5.agent.queue.l2", matches = "true")
class AgentExecutionTaskQueueRuntimeL2Test {

    private static final String IMAGE = "jchatmind-postgres-vchord-bm25:l2";
    private static final String CONTAINER = "jchatmind-agent-queue-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "agent_queue_l2";
    private static final String USER = "agent_queue";
    private static String jdbcUrl;
    private static int port;
    private static boolean containerStarted;
    private static DataSource dataSource;
    private static ChatMessageMapper chatMessageMapper;
    private static AgentExecutionTaskQueue queue;
    private static AgentExecutionTaskEnqueueListener enqueueListener;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startIsolatedPostgres() throws Exception {
        port = requiredPort("g5.agent.queue.pg.port");
        try (ServerSocket ignored = new ServerSocket(port)) {
            ignored.setReuseAddress(true);
        }
        run("docker", "run", "--rm", "-d", "--name", CONTAINER,
                "-e", "POSTGRES_DB=" + DATABASE,
                "-e", "POSTGRES_USER=" + USER,
                "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                "-p", "127.0.0.1:" + port + ":5432", IMAGE);
        containerStarted = true;
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE;
        waitForPostgres(jdbcUrl);
        DriverManagerDataSource source = new DriverManagerDataSource(jdbcUrl, USER, "");
        dataSource = source;
        createBaselineAndApplyQueueMigration(source);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/ChatMessageMapper.xml"),
                new ClassPathResource("mapper/AgentExecutionTaskMapper.xml")
        );
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        chatMessageMapper = template.getMapper(ChatMessageMapper.class);
        enqueueListener = new AgentExecutionTaskEnqueueListener(template.getMapper(AgentExecutionTaskMapper.class));
        queue = new AgentExecutionTaskQueue(template.getMapper(AgentExecutionTaskMapper.class));
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    static void stopIsolatedPostgres() throws Exception {
        if (containerStarted) {
            run("docker", "stop", CONTAINER);
        }
    }

    @Test
    void shouldRollbackMessageTurnLinkAndQueueItemTogether() {
        String sessionId = createSession();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            persistUserTurn(sessionId, "rollback prompt");
            throw new IllegalStateException("force transaction rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force transaction rollback");

        assertThat(countBySession("chat_message", sessionId)).isZero();
        assertThat(countBySession("agent_execution_turn_message", sessionId)).isZero();
        assertThat(countBySession("agent_execution_task", sessionId)).isZero();
        assertThat(countBySession("agent_execution_session_sequence", sessionId)).isZero();
    }

    @Test
    void shouldKeepSessionFifoAcrossWorkersAndReplayAStaleRunningTask() {
        String firstSession = createSession();
        String secondSession = createSession();
        persistUserTurn(firstSession, "first");
        persistUserTurn(firstSession, "second");
        persistUserTurn(secondSession, "independent");

        List<AgentExecutionTask> heads = queue.findRunnableSessionHeads(16);
        assertThat(heads).hasSize(2);
        AgentExecutionTask firstHead = heads.stream()
                .filter(task -> task.getSessionId().equals(firstSession))
                .findFirst().orElseThrow();
        AgentExecutionTask independentHead = heads.stream()
                .filter(task -> task.getSessionId().equals(secondSession))
                .findFirst().orElseThrow();
        assertThat(firstHead.getSequenceNo()).isZero();

        AgentExecutionSessionLockManager firstWorker = new AgentExecutionSessionLockManager(dataSource);
        AgentExecutionSessionLockManager secondWorker = new AgentExecutionSessionLockManager(dataSource);
        AgentExecutionSessionLockManager.SessionLock firstLock = firstWorker.tryLock(firstSession);
        assertThat(firstLock).isNotNull();
        assertThat(secondWorker.tryLock(firstSession)).isNull();
        AgentExecutionTask claimed = queue.claim(firstHead.getId());
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");

        AgentExecutionSessionLockManager.SessionLock independentLock = secondWorker.tryLock(secondSession);
        assertThat(independentLock).isNotNull();
        assertThat(queue.claim(independentHead.getId()).getStatus()).isEqualTo("RUNNING");
        queue.complete(independentHead.getId());
        independentLock.close();

        firstLock.close();
        AgentExecutionSessionLockManager.SessionLock recoveredLock = secondWorker.tryLock(firstSession);
        assertThat(recoveredLock).isNotNull();
        AgentExecutionTask replayed = queue.claim(firstHead.getId());
        assertThat(replayed.getAttemptCount()).isEqualTo(2);
        assertThat(queue.findRunnableSessionHeads(16))
                .filteredOn(task -> task.getSessionId().equals(firstSession))
                .extracting(AgentExecutionTask::getId)
                .containsExactly(firstHead.getId());

        queue.complete(firstHead.getId());
        recoveredLock.close();
        AgentExecutionTask nextHead = queue.findRunnableSessionHeads(16).stream()
                .filter(task -> task.getSessionId().equals(firstSession))
                .findFirst().orElseThrow();
        assertThat(nextHead.getSequenceNo()).isEqualTo(1);
        AgentExecutionTask secondTurn = queue.claim(nextHead.getId());
        assertThat(secondTurn.getStatus()).isEqualTo("RUNNING");
        queue.complete(nextHead.getId());
    }

    @Test
    void shouldDispatchPersistedTurnsInOrderWithTheirTurnContext() {
        String sessionId = createSession();
        persistUserTurn(sessionId, "first");
        persistUserTurn(sessionId, "second");
        long completedBefore = queue.countByStatus("COMPLETED");
        List<Long> executedSequences = new java.util.ArrayList<>();
        AgentExecutionTaskRunner runner = event -> {
            assertThat(AgentExecutionTurnContext.currentSequence()).isEqualTo(event.getSequenceNo());
            assertThat(event.getUserMessageId()).isNotBlank();
            assertThat(event.getUserInput()).isNull();
            executedSequences.add(event.getSequenceNo());
        };
        AgentExecutionTaskWorker worker = new AgentExecutionTaskWorker(
                queue,
                new AgentExecutionSessionLockManager(dataSource),
                runner,
                Runnable::run
        );

        worker.dispatchPendingTasks();
        assertThat(executedSequences).containsExactly(0L);
        worker.dispatchPendingTasks();

        assertThat(executedSequences).containsExactly(0L, 1L);
        assertThat(queue.countByStatus("COMPLETED")).isEqualTo(completedBefore + 2);
        assertThat(queue.findRunnableSessionHeads(16)).noneMatch(task -> task.getSessionId().equals(sessionId));
    }

    @Test
    void shouldReleaseClaimWhenPersistentWorkerExecutorIsSaturatedAndRecoverAfterDrain() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig()
                .agentExecutionWorkerExecutor();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<String> sessionIds = new java.util.ArrayList<>();
        AgentExecutionTaskWorker worker = new AgentExecutionTaskWorker(
                queue,
                new AgentExecutionSessionLockManager(dataSource),
                event -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                },
                executor
        );

        try {
            for (int index = 0; index < 10; index++) {
                String sessionId = createSession();
                sessionIds.add(sessionId);
                persistUserTurn(sessionId, "saturation-" + index);
            }

            worker.dispatchPendingTasks();

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            List<AgentExecutionTask> heads = queue.findRunnableSessionHeads(128).stream()
                    .filter(task -> sessionIds.contains(task.getSessionId()))
                    .toList();
            assertThat(heads).hasSize(10);
            assertThat(heads).filteredOn(task -> task.getStatus().equals("RUNNING")).hasSize(4);
            assertThat(heads).filteredOn(task -> task.getStatus().equals("PENDING")).hasSize(6);
            assertThat(worker.getExecutorSnapshot().rejectedTaskCount()).isEqualTo(1);
            assertThat(worker.getExecutorSnapshot().activeThreads()).isEqualTo(4);
            assertThat(worker.getExecutorSnapshot().queuedTasks()).isZero();

            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline
                    && countTaskStatusBySessions(sessionIds, "COMPLETED") < sessionIds.size()) {
                worker.dispatchPendingTasks();
                Thread.sleep(25);
            }
            assertThat(countTaskStatusBySessions(sessionIds, "COMPLETED")).isEqualTo(10);
            assertThat(countTaskStatusBySessions(sessionIds, "FAILED")).isZero();
            assertThat(countTaskStatusBySessions(sessionIds, "PENDING")).isZero();
            assertThat(countTaskStatusBySessions(sessionIds, "RUNNING")).isZero();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "g5.agent.queue.soak", matches = "true")
    void shouldStayBoundedAndDrainDuringPersistentQueueSoak() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE g5_agent_queue_soak_session (session_id UUID PRIMARY KEY)");
        }
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig()
                .agentExecutionWorkerExecutor();
        AtomicInteger executed = new AtomicInteger();
        AgentExecutionTaskWorker worker = new AgentExecutionTaskWorker(
                queue,
                new AgentExecutionSessionLockManager(dataSource),
                event -> {
                    try {
                        Thread.sleep(10);
                        executed.incrementAndGet();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                },
                executor
        );

        try {
            long submitted = 0;
            int maximumActiveThreads = 0;
            long soakDeadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (System.nanoTime() < soakDeadline) {
                String sessionId = createSession();
                trackSoakSession(sessionId);
                persistUserTurn(sessionId, "persistent-soak-" + submitted);
                submitted++;
                worker.dispatchPendingTasks();

                AgentExecutionTaskWorker.ExecutorSnapshot snapshot = worker.getExecutorSnapshot();
                maximumActiveThreads = Math.max(maximumActiveThreads, snapshot.activeThreads());
                assertThat(snapshot.poolSize()).isLessThanOrEqualTo(4);
                assertThat(snapshot.activeThreads()).isLessThanOrEqualTo(4);
                assertThat(snapshot.queuedTasks()).isZero();
                assertThat(snapshot.remainingQueueCapacity()).isZero();
                Thread.sleep(5);
            }

            assertThat(submitted).isGreaterThan(50);
            assertThat(maximumActiveThreads).isBetween(1, 4);
            long drainDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < drainDeadline
                    && countSoakTasksByStatus("COMPLETED") < submitted) {
                worker.dispatchPendingTasks();
                Thread.sleep(25);
            }
            assertThat(countSoakTasksByStatus("COMPLETED")).isEqualTo(submitted);
            assertThat(countSoakTasksByStatus("FAILED")).isZero();
            assertThat(countSoakTasksByStatus("PENDING")).isZero();
            assertThat(countSoakTasksByStatus("RUNNING")).isZero();
            assertThat(executed.get()).isEqualTo(submitted);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldMakeSessionDeletionWaitForAgentExecutionOnAnotherWorkerConnection() throws Exception {
        String sessionId = createSession();
        AgentExecutionSessionLockManager.SessionLock agentLock =
                new AgentExecutionSessionLockManager(dataSource).tryLock(sessionId);
        assertThat(agentLock).isNotNull();
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch deletionEntered = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var deletion = executor.submit(() -> {
                deletionStarted.countDown();
                new ChatSessionExecutionCoordinator(new AgentExecutionSessionLockManager(dataSource))
                        .execute(sessionId, deletionEntered::countDown);
            });

            assertThat(deletionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(deletionEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            agentLock.close();

            assertThat(deletionEntered.await(2, TimeUnit.SECONDS)).isTrue();
            deletion.get(2, TimeUnit.SECONDS);
        } finally {
            agentLock.close();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReclaimRunningTaskAfterOwningJvmDies() throws Exception {
        String sessionId = createSession();
        persistUserTurn(sessionId, "process recovery");
        long runningBefore = queue.countByStatus("RUNNING");
        long completedBefore = queue.countByStatus("COMPLETED");
        AgentExecutionTask task = queue.findRunnableSessionHeads(1).get(0);
        Path probeDirectory = Files.createTempDirectory("g5-agent-queue-process-");
        Path acquiredFile = probeDirectory.resolve("lock-acquired");
        Path releaseFile = probeDirectory.resolve("release");
        Path outputFile = probeDirectory.resolve("process.log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", classPath,
                AgentExecutionSessionLockProcessProbe.class.getName(),
                jdbcUrl,
                USER,
                sessionId,
                task.getId(),
                acquiredFile.toString(),
                releaseFile.toString()
        ).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (!Files.exists(acquiredFile) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(Files.exists(acquiredFile))
                    .withFailMessage("lock process exited before acquisition: %s",
                            Files.exists(outputFile) ? Files.readString(outputFile) : "no output")
                    .isTrue();
            assertThat(queue.countByStatus("RUNNING")).isEqualTo(runningBefore + 1);
            assertThat(new AgentExecutionSessionLockManager(dataSource).tryLock(sessionId)).isNull();

            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();

            AgentExecutionSessionLockManager.SessionLock recoveredLock = awaitSessionLock(sessionId);
            try {
                AgentExecutionTask recovered = queue.claim(task.getId());
                assertThat(recovered.getAttemptCount()).isEqualTo(2);
                queue.complete(task.getId());
            } finally {
                recoveredLock.close();
            }
            assertThat(queue.countByStatus("COMPLETED")).isEqualTo(completedBefore + 1);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(releaseFile);
            Files.deleteIfExists(acquiredFile);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(probeDirectory);
        }
    }

    @Test
    void shouldReplayAfterJvmDiesAfterPersistingAnExternalSideEffect() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE g5_agent_queue_replay_effect ("
                    + "effect_id BIGSERIAL PRIMARY KEY, task_id UUID NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
        String sessionId = createSession();
        persistUserTurn(sessionId, "replay after side effect");
        AgentExecutionTask task = queue.findRunnableSessionHeads(1).get(0);
        Path probeDirectory = Files.createTempDirectory("g5-agent-queue-replay-");
        Path sideEffectFile = probeDirectory.resolve("side-effect-committed");
        Path releaseFile = probeDirectory.resolve("release");
        Path outputFile = probeDirectory.resolve("process.log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", classPath,
                AgentExecutionTaskSideEffectReplayProcessProbe.class.getName(),
                jdbcUrl,
                USER,
                sessionId,
                task.getId(),
                sideEffectFile.toString(),
                releaseFile.toString()
        ).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(sideEffectFile) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(Files.exists(sideEffectFile))
                    .withFailMessage("Worker did not persist its first external side effect: %s",
                            Files.exists(outputFile) ? Files.readString(outputFile) : "no output")
                    .isTrue();
            assertThat(countTaskSideEffects(task.getId())).isEqualTo(1);
            assertThat(readTaskState(task.getId())).containsExactly("RUNNING", 1);

            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            awaitSessionLock(sessionId).close();

            AgentExecutionTaskRunner replayRunner = event -> recordTaskSideEffect(event.getUserMessageId());
            AgentExecutionTaskWorker recoveryWorker = new AgentExecutionTaskWorker(
                    queue,
                    new AgentExecutionSessionLockManager(dataSource),
                    replayRunner,
                    Runnable::run
            );
            recoveryWorker.dispatchPendingTasks();

            assertThat(countTaskSideEffects(task.getId())).isEqualTo(2);
            assertThat(readTaskState(task.getId())).containsExactly("COMPLETED", 2);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(releaseFile);
            Files.deleteIfExists(sideEffectFile);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(probeDirectory);
        }
    }

    @Test
    void shouldRunQueueWorkersInTwoIndependentJvmProcesses() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE g5_agent_queue_probe_execution ("
                    + "worker_id TEXT NOT NULL, session_id UUID NOT NULL, sequence_no BIGINT NOT NULL, "
                    + "started_at TIMESTAMP NOT NULL, finished_at TIMESTAMP, "
                    + "PRIMARY KEY (worker_id, session_id, sequence_no))");
        }
        Path processDirectory = Files.createTempDirectory("g5-agent-queue-workers-");
        Path workerAReady = processDirectory.resolve("worker-a-ready");
        Path workerAStop = processDirectory.resolve("worker-a-stop");
        Path workerALog = processDirectory.resolve("worker-a.log");
        Path workerBReady = processDirectory.resolve("worker-b-ready");
        Path workerBStop = processDirectory.resolve("worker-b-stop");
        Path workerBLog = processDirectory.resolve("worker-b.log");
        List<Process> workers = new java.util.ArrayList<>();
        List<String> sessions = new java.util.ArrayList<>();
        long completedBefore = queue.countByStatus("COMPLETED");
        long failedBefore = queue.countByStatus("FAILED");
        try {
            workers.add(startWorkerProcess("worker-a", workerAReady, workerAStop, workerALog));
            awaitFile(workerAReady, workers.get(0), workerALog);
            workers.add(startWorkerProcess("worker-b", workerBReady, workerBStop, workerBLog));
            awaitFile(workerBReady, workers.get(1), workerBLog);

            for (int index = 0; index < 6; index++) {
                String sessionId = createSession();
                sessions.add(sessionId);
                persistUserTurn(sessionId, "turn-0-" + index);
                if (index == 0) {
                    persistUserTurn(sessionId, "turn-1-" + index);
                }
            }

            waitForNoActiveTasks(workerALog, workerBLog);
            assertThat(queue.countByStatus("COMPLETED")).isEqualTo(completedBefore + 7);
            assertThat(queue.countByStatus("FAILED")).isEqualTo(failedBefore);
            assertThat(countDistinctProbeWorkers()).isEqualTo(2);
            assertThat(countProbeRows()).isEqualTo(7);
            assertCompletedOnceForSessions(sessions, 7);
            assertSameSessionTurnsNeverOverlap(sessions.get(0));

            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                assertThat(worker.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.exitValue()).isZero();
            }
        } finally {
            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                if (worker.isAlive()) {
                    worker.destroyForcibly();
                    worker.waitFor(10, TimeUnit.SECONDS);
                }
            }
            for (String sessionId : sessions) {
                try (Connection connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("DELETE FROM chat_session WHERE id = ?::uuid")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS g5_agent_queue_probe_execution");
            }
            Files.deleteIfExists(workerAReady);
            Files.deleteIfExists(workerAStop);
            Files.deleteIfExists(workerALog);
            Files.deleteIfExists(workerBReady);
            Files.deleteIfExists(workerBStop);
            Files.deleteIfExists(workerBLog);
            Files.deleteIfExists(processDirectory);
        }
    }

    @Test
    void shouldRunPersistentWorkersInTwoIndependentJvmProcesses() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE g5_agent_queue_probe_execution ("
                    + "worker_id TEXT NOT NULL, session_id UUID NOT NULL, sequence_no BIGINT NOT NULL, "
                    + "started_at TIMESTAMP NOT NULL, finished_at TIMESTAMP, "
                    + "PRIMARY KEY (worker_id, session_id, sequence_no))");
        }
        Path processDirectory = Files.createTempDirectory("g5-agent-queue-workers-");
        Path workerAReady = processDirectory.resolve("worker-a-ready");
        Path workerAStop = processDirectory.resolve("worker-a-stop");
        Path workerALog = processDirectory.resolve("worker-a.log");
        Path workerBReady = processDirectory.resolve("worker-b-ready");
        Path workerBStop = processDirectory.resolve("worker-b-stop");
        Path workerBLog = processDirectory.resolve("worker-b.log");
        List<Process> workers = new java.util.ArrayList<>();
        List<String> sessions = new java.util.ArrayList<>();
        long completedBefore = queue.countByStatus("COMPLETED");
        long failedBefore = queue.countByStatus("FAILED");
        try {
            workers.add(startWorkerProcess("worker-a", workerAReady, workerAStop, workerALog));
            awaitFile(workerAReady, workers.get(0), workerALog);
            workers.add(startWorkerProcess("worker-b", workerBReady, workerBStop, workerBLog));
            awaitFile(workerBReady, workers.get(1), workerBLog);

            for (int index = 0; index < 6; index++) {
                String sessionId = createSession();
                sessions.add(sessionId);
                persistUserTurn(sessionId, "turn-0-" + index);
                if (index == 0) {
                    persistUserTurn(sessionId, "turn-1-" + index);
                }
            }

            waitForNoActiveTasks(workerALog, workerBLog);
            assertThat(queue.countByStatus("COMPLETED")).isEqualTo(completedBefore + 7);
            assertThat(queue.countByStatus("FAILED")).isEqualTo(failedBefore);
            assertThat(countDistinctProbeWorkers()).isEqualTo(2);
            assertThat(countProbeRows()).isEqualTo(7);
            assertSameSessionTurnsNeverOverlap(sessions.get(0));

            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                assertThat(worker.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.exitValue()).isZero();
            }
        } finally {
            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                if (worker.isAlive()) {
                    worker.destroyForcibly();
                    worker.waitFor(10, TimeUnit.SECONDS);
                }
            }
            for (String sessionId : sessions) {
                try (Connection connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("DELETE FROM chat_session WHERE id = ?::uuid")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
            }
            Files.deleteIfExists(workerAReady);
            Files.deleteIfExists(workerAStop);
            Files.deleteIfExists(workerALog);
            Files.deleteIfExists(workerBReady);
            Files.deleteIfExists(workerBStop);
            Files.deleteIfExists(workerBLog);
            Files.deleteIfExists(processDirectory);
        }
    }

    private static String createSession() {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("INSERT INTO chat_session (id, user_id) VALUES (?::uuid, '7')")) {
            statement.setString(1, id);
            statement.executeUpdate();
            return id;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Process startWorkerProcess(String workerId, Path readyFile, Path stopFile, Path outputFile)
            throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        return new ProcessBuilder(
                javaExecutable,
                "-cp", classPath,
                AgentExecutionTaskQueueWorkerProcessProbe.class.getName(),
                jdbcUrl,
                USER,
                workerId,
                readyFile.toString(),
                stopFile.toString()
        ).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
    }

    private static void assertCompletedOnceForSessions(List<String> sessions, long expectedCount)
            throws Exception {
        String placeholders = String.join(",", java.util.Collections.nCopies(sessions.size(), "?::uuid"));
        String sql = "SELECT COUNT(*) FROM agent_execution_task WHERE session_id IN (" + placeholders + ") "
                + "AND status = 'COMPLETED' AND attempt_count = 1";
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < sessions.size(); index++) {
                statement.setString(index + 1, sessions.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getLong(1)).isEqualTo(expectedCount);
            }
        }
    }

    private static void awaitFile(Path readyFile, Process worker, Path outputFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(readyFile) && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(Files.exists(readyFile))
                .withFailMessage("queue worker process did not become ready: %s",
                        Files.exists(outputFile) ? Files.readString(outputFile) : "no output")
                .isTrue();
    }

    private static void waitForNoActiveTasks(Path workerALog, Path workerBLog) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.countByStatus("PENDING") == 0 && queue.countByStatus("RUNNING") == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Two JVM queue workers did not drain the queue. worker-a="
                + Files.readString(workerALog) + "; worker-b=" + Files.readString(workerBLog));
    }

    private static long countDistinctProbeWorkers() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(DISTINCT worker_id) FROM g5_agent_queue_probe_execution")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static long countProbeRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM g5_agent_queue_probe_execution")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void assertSameSessionTurnsNeverOverlap(String sessionId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM g5_agent_queue_probe_execution first_turn
                     JOIN g5_agent_queue_probe_execution second_turn
                       ON second_turn.session_id = first_turn.session_id
                      AND second_turn.sequence_no = 1
                     WHERE first_turn.session_id = ?::uuid
                       AND first_turn.sequence_no = 0
                       AND (first_turn.finished_at IS NULL OR second_turn.started_at < first_turn.finished_at)
                     """)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getLong(1)).isZero();
            }
        }
    }

    private static void persistUserTurn(String sessionId, String input) {
        transactionTemplate.executeWithoutResult(status -> {
            long sequenceNo = chatMessageMapper.reserveAgentTurnSequence(sessionId);
            ChatMessage message = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("user")
                    .content(input)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            if (chatMessageMapper.insert(message) != 1) {
                throw new IllegalStateException("user message insert failed");
            }
            chatMessageMapper.insertAgentTurnMessageLink(message.getId(), sessionId, sequenceNo);
            enqueueListener.enqueue(new ChatEvent("7", "agent-1", sessionId,
                    message.getId(), sequenceNo, input));
        });
    }

    private static long countBySession(String table, String sessionId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE session_id = ?::uuid")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long countTaskSideEffects(String taskId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM g5_agent_queue_replay_effect WHERE task_id = ?::uuid")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countTaskStatusBySessions(List<String> sessionIds, String status) throws Exception {
        long count = 0;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM agent_execution_task "
                             + "WHERE session_id = ?::uuid AND status = ?")) {
            for (String sessionId : sessionIds) {
                statement.setString(1, sessionId);
                statement.setString(2, status);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    count += resultSet.getLong(1);
                }
            }
        }
        return count;
    }

    private static void trackSoakSession(String sessionId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO g5_agent_queue_soak_session (session_id) VALUES (?::uuid)")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long countSoakTasksByStatus(String status) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM agent_execution_task task "
                             + "JOIN g5_agent_queue_soak_session soak ON soak.session_id = task.session_id "
                             + "WHERE task.status = ?")) {
            statement.setString(1, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<Object> readTaskState(String taskId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status, attempt_count FROM agent_execution_task WHERE id = ?::uuid")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AssertionError("Persisted task was not found");
                }
                return List.of(resultSet.getString(1), resultSet.getInt(2));
            }
        }
    }

    private static void recordTaskSideEffect(String userMessageId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO g5_agent_queue_replay_effect (task_id) "
                             + "SELECT id FROM agent_execution_task WHERE user_message_id = ?::uuid")) {
            statement.setString(1, userMessageId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("replayed task side effect was not recorded");
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AgentExecutionSessionLockManager.SessionLock awaitSessionLock(String sessionId)
            throws InterruptedException {
        AgentExecutionSessionLockManager manager = new AgentExecutionSessionLockManager(dataSource);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            AgentExecutionSessionLockManager.SessionLock lock = manager.tryLock(sessionId);
            if (lock != null) {
                return lock;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Session advisory lock was not released when the worker JVM exited");
    }

    private static void createBaselineAndApplyQueueMigration(DataSource source) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_session (id UUID PRIMARY KEY, user_id TEXT NOT NULL)");
            statement.execute("CREATE TABLE chat_message (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
                    + "session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE, "
                    + "role VARCHAR(32) NOT NULL, content TEXT, metadata JSONB, "
                    + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            String migration = Files.readString(Path.of("..", "sql", "chat",
                    "2026-10-21-create-agent-execution-queue.sql"));
            statement.execute(migration);
        }
    }

    private static void waitForPostgres(String url) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(url, USER, "")) {
                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("isolated PostgreSQL did not become ready", lastFailure);
    }

    private static int requiredPort(String name) {
        String value = System.getProperty(name);
        if (value == null || !value.matches("55[0-9]{3}")) {
            throw new IllegalArgumentException("An isolated PostgreSQL port is required");
        }
        return Integer.parseInt(value);
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream ignored = process.getOutputStream()) {
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("isolated PostgreSQL command failed: " + output);
        }
        return output;
    }
}
