package dev.promisegraph.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Privacy settings, bound from {@code promisegraph.privacy.*}.
 *
 * @param admins people who may use the operations pages besides team leads, e.g. yourself on a
 *     personal Gmail setup where there is no team
 */
@ConfigurationProperties("promisegraph.privacy")
public record PrivacyProperties(List<String> admins) {

    public PrivacyProperties {
        admins = admins == null ? List.of() : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
    }
}
