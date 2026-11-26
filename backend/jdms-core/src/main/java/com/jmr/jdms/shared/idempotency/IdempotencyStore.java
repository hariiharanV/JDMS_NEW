package com.jmr.jdms.shared.idempotency;

import com.jmr.jdms.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replay protection for write requests ({@code docs/04-api-outline.md}): the same caller sending the same key with
 * the same request gets the original response for 24 hours; the same key with a different request is a 409.
 */
@Component
public class IdempotencyStore {

    public static final String IDEMPOTENCY_KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private final IdempotencyRecordRepository records;

    IdempotencyStore(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /** A stored response to replay. */
    public record StoredResponse(int status, String body) {
    }

    /** Validates a required key: 400 when missing or malformed. */
    public static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY_REQUIRED,
                    "An Idempotency-Key header of 8-100 characters (A-Z, a-z, 0-9, . _ : -) is required.");
        }
        return key;
    }

    /**
     * The response to replay for this caller and key, if the request matches the stored one. Expired records are
     * discarded. A different request with the same key fails with 409 {@code IDEMPOTENCY_KEY_REUSED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredResponse> replay(String clientId, String key, String requestHash) {
        Optional<IdempotencyRecord> existing = records.findByClientIdAndKey(clientId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();
        if (record.getExpiresAt().isBefore(Instant.now())) {
            records.delete(record);
            records.flush();
            return Optional.empty();
        }
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        return Optional.of(new StoredResponse(record.getResponseStatus(), record.getResponse()));
    }

    /** Stores the outcome in the caller's transaction, so it commits exactly with the change it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(String clientId, String key, String requestHash, int status, String body) {
        records.saveAndFlush(new IdempotencyRecord(clientId, key, requestHash, status, body,
                Instant.now().plus(RETENTION).truncatedTo(ChronoUnit.MICROS)));
    }
}
