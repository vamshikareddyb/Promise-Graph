package dev.promisegraph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.ingestion.IngestionGateway;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The Spring wiring loads. Workers stay off so no Temporal server is needed (stubs connect lazily). */
@SpringBootTest(properties = "promisegraph.temporal.worker-enabled=false")
class ApplicationContextTest {

    @Autowired
    Repository repository;

    @Autowired
    IngestionGateway gateway;

    @Test
    void contextLoads() {
        assertThat(repository).isInstanceOf(InMemoryRepository.class);
        assertThat(gateway).isNotNull();
    }
}
