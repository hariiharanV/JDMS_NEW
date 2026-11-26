package com.jmr.jdms.platform.jobs.infra;

import com.jmr.jdms.platform.ids.TimeOrderedIds;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL adapter for the job queue (native SQL lives here only; the Oracle adapter follows the same statements
 * with {@code FOR UPDATE SKIP LOCKED}). All values are bound parameters. Writes keep {@code version_lock} and the
 * audit columns current, as the JPA mapping in the core expects.
 */
public class PostgresJobStore implements JobStore {

    static final String SYSTEM = "system";
    private static final int MAX_ERROR = 2000;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresJobStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<ClaimedJob> claim(Set<String> types, String runner, int limit, Duration lease) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        Instant now = now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("types", List.copyOf(types))
                .addValue("now", Timestamp.from(now))
                .addValue("until", Timestamp.from(now.plus(lease)))
                .addValue("runner", runner)
                .addValue("limit", limit)
                .addValue("system", SYSTEM);
        return jdbc.query("""
                update job set status = 'RUNNING', attempts = attempts + 1, locked_by = :runner,
                       locked_until = :until, updated_at = :now, updated_by = :system, version_lock = version_lock + 1
                 where id in (select id from job
                               where type in (:types)
                                 and ((status = 'READY' and run_after <= :now)
                                      or (status = 'RUNNING' and locked_until < :now))
                               order by run_after
                               limit :limit
                               for update skip locked)
                returning id, tenant_id, type, payload::text as payload, attempts, max_attempts
                """, params, (rs, row) -> new ClaimedJob(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("type"), rs.getString("payload"),
                rs.getInt("attempts"), rs.getInt("max_attempts")));
    }

    @Override
    public int extend(Collection<UUID> ids, String runner, Duration lease) {
        if (ids.isEmpty()) {
            return 0;
        }
        Instant now = now();
        return jdbc.update("""
                update job set locked_until = :until, updated_at = :now, updated_by = :system,
                       version_lock = version_lock + 1
                 where id in (:ids) and locked_by = :runner and status = 'RUNNING'
                """, new MapSqlParameterSource().addValue("ids", List.copyOf(ids)).addValue("runner", runner)
                .addValue("until", Timestamp.from(now.plus(lease))).addValue("now", Timestamp.from(now))
                .addValue("system", SYSTEM));
    }

    @Override
    public void complete(UUID id) {
        finish(id, "DONE", null, null);
    }

    @Override
    public void retryAt(UUID id, Instant runAfter, String error) {
        finish(id, "READY", runAfter, error);
    }

    @Override
    public void fail(UUID id, String error) {
        finish(id, "FAILED", null, error);
    }

    @Override
    public void dead(UUID id, String error) {
        finish(id, "DEAD", null, error);
    }

    @Override
    public UUID enqueue(NewJob job, int maxAttempts) {
        UUID id = TimeOrderedIds.next();
        Timestamp now = Timestamp.from(now());
        jdbc.update("""
                insert into job (id, tenant_id, type, payload, status, attempts, max_attempts, run_after,
                                 created_at, created_by, updated_at, updated_by, version_lock)
                values (:id, :tenant, :type, cast(:payload as jsonb), 'READY', 0, :max, :now,
                        :now, :system, :now, :system, 0)
                """, Map.of("id", id, "tenant", job.tenantId(), "type", job.type(), "payload", job.payload(),
                "max", maxAttempts, "now", now, "system", SYSTEM));
        return id;
    }

    private void finish(UUID id, String status, Instant runAfter, String error) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("runAfter", runAfter == null ? null : Timestamp.from(runAfter), Types.TIMESTAMP)
                .addValue("error", error == null ? null : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR)
                        : error, Types.VARCHAR)
                .addValue("now", Timestamp.from(now()))
                .addValue("system", SYSTEM);
        jdbc.update("""
                update job set status = :status, run_after = coalesce(:runAfter, run_after), last_error = :error,
                       locked_by = null, locked_until = null, updated_at = :now, updated_by = :system,
                       version_lock = version_lock + 1
                 where id = :id
                """, params);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
