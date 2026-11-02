package dev.promisegraph.ingestion;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.sim.Dataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Replays the simulated company's messages through the gateway, in time order, flushing at the
 * end of each simulated day the way a live gateway flushes on its timer. Then exits.
 *
 * <pre>./gradlew bootRun --args='--spring.profiles.active=replay --promisegraph.temporal.worker-enabled=false'</pre>
 */
@Component
@Profile("replay")
public class ReplayRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayRunner.class);

    private final IngestionGateway gateway;
    private final PromiseGraphProperties props;
    private final ConfigurableApplicationContext context;

    public ReplayRunner(IngestionGateway gateway, PromiseGraphProperties props, ConfigurableApplicationContext context) {
        this.gateway = gateway;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        var rows = Dataset.load(props.tenant());
        String day = null;
        for (var row : rows) {
            String today = row.message().ts().substring(0, 10);
            if (day != null && !day.equals(today)) {
                gateway.flushAll();
            }
            day = today;
            gateway.ingest(row.message());
        }
        gateway.flushAll();
        log.info("replayed {} messages", rows.size());
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
