package com.jmr.jdms.migration.config;

import com.jmr.jdms.migration.legacy.LegacyFileCipher;
import com.jmr.jdms.migration.legacy.LegacyFileStore;
import com.jmr.jdms.migration.legacy.LegacyRepository;
import com.jmr.jdms.migration.run.CatalogProposer;
import com.jmr.jdms.migration.run.MigrationRunner;
import com.jmr.jdms.migration.client.ClientCredentialsTokenProvider;
import com.jmr.jdms.migration.client.CoreClient;
import com.jmr.jdms.migration.client.TokenProvider;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Beans of a run; the context is lazy (migration.yml), so the tool starts without any configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
class MigrationConfiguration {

    @Bean
    LegacyFileCipher legacyFileCipher(MigrationProperties properties) {
        return LegacyFileCipher.fromSecretFile(properties.secretFile());
    }

    @Bean
    LegacyFileStore legacyFileStore(MigrationProperties properties) {
        return new LegacyFileStore(properties.filesRoot(), properties.maxFileMb() * 1024L * 1024L);
    }

    @Bean
    @ConditionalOnMissingBean
    TokenProvider tokenProvider(MigrationProperties properties, RestClient.Builder builder) {
        MigrationProperties.Core core = properties.core();
        return new ClientCredentialsTokenProvider(builder, core.tokenUri(), core.clientId(), core.clientSecret(),
                Clock.systemUTC());
    }

    @Bean
    CoreClient coreClient(MigrationProperties properties, RestClient.Builder builder, TokenProvider tokens,
            JsonMapper jsonMapper) {
        return new CoreClient(builder, properties.core().url(), tokens, jsonMapper);
    }

    @Bean
    MigrationRunner migrationRunner(MigrationProperties properties, LegacyRepository legacy, LegacyFileStore files,
            LegacyFileCipher cipher, CoreClient core) {
        return new MigrationRunner(properties, legacy, files, cipher, core);
    }

    @Bean
    CatalogProposer catalogProposer(MigrationProperties properties, LegacyRepository legacy, CoreClient core) {
        return new CatalogProposer(legacy, core, properties.mapping());
    }
}
