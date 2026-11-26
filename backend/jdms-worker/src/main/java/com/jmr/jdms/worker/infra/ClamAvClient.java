package com.jmr.jdms.worker.infra;

import com.jmr.jdms.platform.jobs.PermanentJobFailure;
import com.jmr.jdms.worker.config.WorkerProperties;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * clamd over TCP with the INSTREAM command (ClamAV runs as a separate service, ADR-0005). The file is streamed in
 * chunks; clamd's own {@code StreamMaxLength} must be at least the worker's maximum input size.
 */
@Component
public class ClamAvClient {

    /** {@code signature} is set when malware was found. */
    public record Verdict(boolean infected, String signature) {
    }

    private static final int CHUNK = 64 * 1024;

    private final WorkerProperties.ClamAv settings;

    ClamAvClient(WorkerProperties properties) {
        this.settings = properties.clamav();
    }

    /**
     * @throws IOException on connection problems or timeouts (retried)
     * @throws PermanentJobFailure when clamd refuses the stream (e.g. too large)
     */
    public Verdict scan(InputStream content) throws IOException {
        try (Socket socket = new Socket()) {
            int timeout = (int) settings.timeout().toMillis();
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), timeout);
            socket.setSoTimeout(timeout);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int read;
            try {
                while ((read = content.read(buffer)) != -1) {
                    out.writeInt(read);
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                if (e instanceof SocketException) {
                    // clamd closes the connection once its stream limit is exceeded; its reply says why
                    return verdict(reply(socket.getInputStream()));
                }
                throw e;
            }
            out.writeInt(0);
            out.flush();
            return verdict(reply(socket.getInputStream()));
        }
    }

    static Verdict verdict(String reply) throws IOException {
        // "stream: OK", "stream: <signature> FOUND", "INSTREAM size limit exceeded. ERROR", "... ERROR"
        String text = reply.strip();
        if (text.endsWith(" OK")) {
            return new Verdict(false, null);
        }
        if (text.endsWith(" FOUND")) {
            String signature = text.substring(text.indexOf(':') + 1, text.length() - " FOUND".length()).strip();
            return new Verdict(true, signature.length() > 200 ? signature.substring(0, 200) : signature);
        }
        if (text.contains("size limit exceeded")) {
            throw new PermanentJobFailure("Antivirus refused the file: larger than its stream limit");
        }
        throw new IOException("Unexpected antivirus reply: " + (text.length() > 100 ? text.substring(0, 100) : text));
    }

    private static String reply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0 && reply.size() < 1024) {
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
