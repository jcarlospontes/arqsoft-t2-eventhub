package br.uece.eventhub.registration;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Acesso JDBC minimo: pool fixo de conexoes + transacao com commit/rollback automaticos. */
final class Db {

    @FunctionalInterface
    interface Work<T> { T run(Connection c) throws SQLException; }

    static final class DuplicateKey extends RuntimeException {
        DuplicateKey(String m) { super(m); }
    }

    private final String url, user, password;
    private final BlockingQueue<Connection> pool;

    Db(String url, String user, String password, int size) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) pool.add(open());
    }

    private Connection open() {
        for (int attempt = 1; ; attempt++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                if (attempt >= 30) throw new IllegalStateException("banco indisponivel", e);
                Log.info("banco indisponivel, nova tentativa (" + attempt + ")");
                sleep(1000);
            }
        }
    }

    /** Executa {@code work} em uma transacao. Excecoes provocam rollback. */
    <T> T tx(Work<T> work) {
        Connection c;
        try {
            c = pool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            if (!c.isValid(1)) { safeClose(c); c = open(); }
            c.setAutoCommit(false);
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            rollback(c);
            if ("23505".equals(e.getSQLState())) throw new DuplicateKey(e.getMessage());
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            rollback(c);
            throw e;
        } finally {
            pool.add(c);
        }
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    static List<Map<String, Object>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof Timestamp ts) v = ts.toInstant();
                    row.put(md.getColumnLabel(i), v);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    static Optional<Map<String, Object>> one(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(c, sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Instant in) ps.setTimestamp(i + 1, Timestamp.from(in));
            else ps.setObject(i + 1, p);
        }
        return ps;
    }

    private static void rollback(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private static void safeClose(Connection c) {
        try { c.close(); } catch (SQLException ignored) { }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
