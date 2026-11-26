package com.jmr.jdms.perf.check;

import com.jmr.jdms.perf.infra.PerfDataWriter;
import com.jmr.jdms.perf.infra.PerfDataWriter.StoredVersion;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Restore evidence (B-13): decrypts a random sample of stored versions, of every tenant, with the configured key and
 * compares each with the SHA-256 recorded at upload. Run against a restored database and object store, it proves the
 * content, the wrapped data keys and the tenant key were all recovered; with another key nothing decrypts.
 */
@Service
public class ContentVerifier {

    private static final Logger log = LoggerFactory.getLogger(ContentVerifier.class);

    private final PerfDataWriter data;
    private final EnvelopeEncryption encryption;

    ContentVerifier(PerfDataWriter data, EnvelopeEncryption encryption) {
        this.data = data;
        this.encryption = encryption;
    }

    public record Result(int sampled, int matching, int different, int unreadable) {

        public boolean allMatch() {
            return sampled > 0 && matching == sampled;
        }
    }

    public Result verify(int sample) {
        List<StoredVersion> versions = data.randomVersions(sample);
        int matching = 0;
        int different = 0;
        int unreadable = 0;
        for (StoredVersion version : versions) {
            try (InputStream content = encryption.open(version.tenantId(), version.blob())) {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    sha256.update(buffer, 0, read);
                }
                if (HexFormat.of().formatHex(sha256.digest()).equals(version.blob().sha256())) {
                    matching++;
                } else {
                    different++;
                }
            } catch (IOException | RuntimeException e) {
                unreadable++;
                log.debug("Version {} unreadable: {}", version.versionId(), e.getClass().getSimpleName());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return new Result(versions.size(), matching, different, unreadable);
    }
}
