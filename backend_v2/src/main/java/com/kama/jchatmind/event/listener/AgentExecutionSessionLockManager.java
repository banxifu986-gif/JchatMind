package com.kama.jchatmind.event.listener;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
@AllArgsConstructor
public class AgentExecutionSessionLockManager {

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";
    private static final String LOCK_SQL = "SELECT pg_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;

    public SessionLock tryLock(String sessionId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || !resultSet.getBoolean(1)) {
                        connection.close();
                        return null;
                    }
                }
            }
            return new SessionLock(connection, sessionId);
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new IllegalStateException("Unable to acquire agent execution session lock", exception);
        }
    }

    public SessionLock lock(String sessionId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Agent execution session lock was not acquired");
                    }
                }
            }
            return new SessionLock(connection, sessionId);
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new IllegalStateException("Unable to acquire agent execution session lock", exception);
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public static final class SessionLock implements AutoCloseable {

        private final Connection connection;
        private final String sessionId;
        private boolean closed;

        private SessionLock(Connection connection, String sessionId) {
            this.connection = connection;
            this.sessionId = sessionId;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || !resultSet.getBoolean(1)) {
                        throw new IllegalStateException("Agent execution session lock was not held");
                    }
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to release agent execution session lock", exception);
            } finally {
                try {
                    connection.close();
                } catch (SQLException exception) {
                    throw new IllegalStateException("Unable to close agent execution session lock", exception);
                }
            }
        }
    }
}
