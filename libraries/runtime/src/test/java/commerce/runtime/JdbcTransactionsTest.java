package commerce.runtime;

import java.sql.Connection;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcTransactionsTest {
    @Mock private JdbcTemplate jdbc;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;

    @BeforeEach
    void setup() {
        when(jdbc.getDataSource()).thenReturn(dataSource);
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void requiresAnActualTransaction() {
        assertThatThrownBy(() -> JdbcTransactions.requireWritable(jdbc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnUnrelatedTransactionWithoutLocalJdbcResource() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> JdbcTransactions.requireWritable(jdbc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void synchronizedAutocommitConnectionIsNotMistakenForJdbcTransaction() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> JdbcTransactions.requireWritable(jdbc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsOnlyWritableTransactionalLocalConnection() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        when(connection.getAutoCommit()).thenReturn(false);
        JdbcTransactions.requireWritable(jdbc);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> JdbcTransactions.requireWritable(jdbc)).isInstanceOf(IllegalStateException.class);
    }
}
