package com.jmr.jdms.perf;

import com.jmr.jdms.perf.check.ContentVerifier;
import com.jmr.jdms.perf.generate.DocumentGenerator;
import com.jmr.jdms.perf.infra.PerfDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * {@code seed} creates the PERF tenant and its catalog; {@code generate} also fills it with synthetic documents up to
 * {@code --jdms.perf.documents=<n>}; {@code verify-content} decrypts a random sample of stored versions
 * ({@code --sample=<n>}, default 500) and compares them with their recorded SHA-256. Exit code 0: done (every sampled
 * version matched); 3: some did not; 2: unknown command; 1: failed.
 */
@Component
class PerfCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(PerfCommand.class);

    private final ObjectProvider<PerfDataWriter> writer;
    private final ObjectProvider<DocumentGenerator> generator;
    private final ObjectProvider<ContentVerifier> verifier;
    private int exitCode;

    PerfCommand(ObjectProvider<PerfDataWriter> writer, ObjectProvider<DocumentGenerator> generator,
            ObjectProvider<ContentVerifier> verifier) {
        this.writer = writer;
        this.generator = generator;
        this.verifier = verifier;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = args.getNonOptionArgs().isEmpty() ? "" : args.getNonOptionArgs().getFirst();
        switch (command) {
            case "seed" -> {
                writer.getObject().seed();
                log.info("Tenant PERF and its catalog are in place");
            }
            case "generate" -> {
                DocumentGenerator.Summary summary = generator.getObject().generate();
                log.info("Generated {} documents in {} s ({} per hour); the tenant now holds {}", summary.written(),
                        summary.elapsed().toSeconds(), Math.round(summary.perHour()), summary.after());
            }
            case "verify-content" -> {
                int sample = args.containsOption("sample") ? Integer.parseInt(args.getOptionValues("sample").getFirst())
                        : 500;
                ContentVerifier.Result result = verifier.getObject().verify(sample);
                log.info("Content check: {} sampled, {} matching, {} different, {} unreadable", result.sampled(),
                        result.matching(), result.different(), result.unreadable());
                exitCode = result.allMatch() ? 0 : 3;
            }
            default -> {
                log.info("Usage: jdms-perf seed | generate [--jdms.perf.documents=<n>] | verify-content [--sample=<n>]");
                exitCode = command.isEmpty() ? 0 : 2;
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
