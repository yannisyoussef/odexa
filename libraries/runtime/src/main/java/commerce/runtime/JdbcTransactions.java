package commerce.runtime;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JdbcTransactions {
    private JdbcTransactions() {
    }

    static void requireWritable(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)) {
            throw new IllegalStateException("A writable transaction on the local JDBC database is required");
        }
        try {
            // A Kafka transaction may synchronize a JDBC connection WITHOUT disabling autocommit.
            // Mere thread-bound resource presence must never authorize an outbox/inbox write.
            if (holder.getConnection().getAutoCommit()) {
                throw new IllegalStateException("The local JDBC connection must not use autocommit");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify the local JDBC transaction");
        }
    }
}
