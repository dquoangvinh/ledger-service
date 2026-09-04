package com.ecommerce.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL container the ledger tests run against, imported by
 * {@link AbstractLedgerIntegrationTest}.
 *
 * <p>Top level rather than nested inside the test class on purpose. As a static nested class it was
 * picked up a second time as a "default configuration class" of the test hierarchy, which Spring
 * currently ignores while logging a warning - and which Spring Framework 7.1 will stop ignoring,
 * leaving the container declared twice. Moving it out is the fix the Spring reference guide
 * recommends for exactly this warning.
 */
@TestConfiguration(proxyBeanMethods = false)
class LedgerContainers {

    /**
     * Tracks the major version the services run, currently {@code pgvector/pgvector:pg18}. Testing
     * on a different major than production would leave planner and locking differences undetected -
     * which is exactly the class of behaviour these tests exist to pin down.
     *
     * <p>Plain {@code postgres} rather than the pgvector image because the ledger stores no
     * embeddings; only the engine version has to match.
     */
    @Bean
    @ServiceConnection
    // Testcontainers 2.x: PostgreSQLContainer is no longer generic, and it moved from
    // org.testcontainers.containers to its own module package.
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                // @ServiceConnection would work without these - it reads whatever the container
                // reports - but the defaults are all called "test", which is useless when several
                // test containers are running and you are trying to find this one in `docker ps`
                // or point a database client at it.
                .withDatabaseName("payment_ledger_test_db")
                .withUsername("test")
                .withPassword("test")
                // Honoured only when testcontainers.reuse.enable=true locally; ignored in CI.
                // Note that every setting above feeds the reuse hash: change any of them and the
                // existing container no longer matches, so a new one starts and the old one is
                // left behind - reuse also switches off Ryuk's cleanup.
                .withReuse(true);
    }
}
