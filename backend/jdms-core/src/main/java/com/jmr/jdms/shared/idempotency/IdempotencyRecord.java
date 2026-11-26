package com.jmr.jdms.shared.idempotency;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The stored outcome of a write request made with an {@code Idempotency-Key} (DM {@code idempotency_key}). */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "idem_key", nullable = false, length = 100)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String response;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    IdempotencyRecord(String clientId, String key, String requestHash, int responseStatus, String response,
            Instant expiresAt) {
        this.id = Ids.newId();
        this.clientId = clientId;
        this.key = key;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.response = response;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponse() {
        return response;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
