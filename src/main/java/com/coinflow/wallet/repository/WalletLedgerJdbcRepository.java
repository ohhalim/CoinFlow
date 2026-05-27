package com.coinflow.wallet.repository;

import com.coinflow.wallet.domain.WalletLedger;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

@Repository
public class WalletLedgerJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO wallet_ledgers (
                user_id,
                wallet_id,
                asset,
                type,
                delta_available,
                delta_locked,
                available_balance_after,
                locked_balance_after,
                order_id,
                trade_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public WalletLedgerJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(WalletLedger ledger) {
        saveAll(List.of(ledger));
    }

    public void saveAll(List<WalletLedger> ledgers) {
        if (ledgers.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                WalletLedger ledger = ledgers.get(index);
                ps.setLong(1, ledger.getUserId());
                ps.setLong(2, ledger.getWalletId());
                ps.setString(3, ledger.getAsset());
                ps.setString(4, ledger.getType().name());
                ps.setBigDecimal(5, ledger.getDeltaAvailable());
                ps.setBigDecimal(6, ledger.getDeltaLocked());
                ps.setBigDecimal(7, ledger.getAvailableBalanceAfter());
                ps.setBigDecimal(8, ledger.getLockedBalanceAfter());
                setNullableLong(ps, 9, ledger.getOrderId());
                setNullableLong(ps, 10, ledger.getTradeId());
            }

            @Override
            public int getBatchSize() {
                return ledgers.size();
            }
        });
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
            return;
        }
        ps.setLong(index, value);
    }
}
