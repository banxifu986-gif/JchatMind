package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.mapper.AgentExecutionTaskMapper;
import com.kama.jchatmind.service.AgentExecutionTaskQueue;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

public final class AgentExecutionTaskSideEffectReplayProcessProbe {

    private AgentExecutionTaskSideEffectReplayProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("JDBC URL, user, session, task and marker paths are required");
        }
        DataSource dataSource = new DriverManagerDataSource(args[0], args[1], "");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/AgentExecutionTaskMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        AgentExecutionTaskQueue queue = new AgentExecutionTaskQueue(
                new SqlSessionTemplate(factory).getMapper(AgentExecutionTaskMapper.class));
        var head = queue.findRunnableSessionHeads(1).stream().findFirst().orElseThrow();
        if (!head.getSessionId().equals(args[2]) || !head.getId().equals(args[3])) {
            throw new IllegalStateException("Expected persisted task is not the runnable session head");
        }
        AgentExecutionTaskRunner runner = event -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO g5_agent_queue_replay_effect (task_id) "
                                 + "SELECT id FROM agent_execution_task WHERE user_message_id = ?::uuid")) {
                statement.setString(1, event.getUserMessageId());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("task side effect was not recorded");
                }
                Files.writeString(Path.of(args[4]), "COMMITTED");
                while (!Files.exists(Path.of(args[5]))) {
                    Thread.sleep(25);
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        AgentExecutionTaskWorker worker = new AgentExecutionTaskWorker(
                queue,
                new AgentExecutionSessionLockManager(dataSource),
                runner,
                Runnable::run
        );
        worker.dispatchPendingTasks();
    }
}
