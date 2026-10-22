package com.kama.jchatmind.event.listener;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

public final class AgentExecutionSessionLockProcessProbe {

    private AgentExecutionSessionLockProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("JDBC URL, user, session, task and marker paths are required");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(args[0], args[1], "");
        try (AgentExecutionSessionLockManager.SessionLock ignored =
                     new AgentExecutionSessionLockManager(dataSource).lock(args[2]);
             Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE agent_execution_task SET status = 'RUNNING', "
                             + "attempt_count = attempt_count + 1, started_at = CURRENT_TIMESTAMP "
                             + "WHERE id = CAST(? AS uuid) AND status = 'PENDING'")) {
            statement.setString(1, args[3]);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Process probe could not claim the pending task");
            }
            Files.writeString(Path.of(args[4]), "LOCKED");
            while (!Files.exists(Path.of(args[5]))) {
                Thread.sleep(25);
            }
        }
    }
}
