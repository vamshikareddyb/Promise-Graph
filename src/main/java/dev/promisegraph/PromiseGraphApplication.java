package dev.promisegraph;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PromiseGraphApplication {

    public static void main(String[] args) {
        useUtc();
        SpringApplication.run(PromiseGraphApplication.class, args);
    }

    /**
     * Runs the app in UTC whatever the computer's time zone. Promise Graph already works in UTC
     * (dates, working hours, timers), and the Postgres driver sends the JVM's zone name when it
     * connects: on a computer set to India, Java names it "Asia/Calcutta", which PostgreSQL 16
     * rejects, so every database call failed. Log times are in UTC too.
     */
    static void useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }
}
