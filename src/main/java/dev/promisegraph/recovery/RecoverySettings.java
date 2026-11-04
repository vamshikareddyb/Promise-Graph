package dev.promisegraph.recovery;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.handoff.Directory;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Limits and facts the recovery planners and the verifier share.
 *
 * @param capacity the most open promises (including ones they help with) a person should carry
 * @param minTeams a slip that affects at least this many teams starts a negotiation
 * @param customers customer names; a promise mentioning one is treated as made to a customer
 */
public record RecoverySettings(int capacity, int minTeams, List<String> customers) {

    public static final int DEFAULT_CAPACITY = 8;
    public static final int DEFAULT_MIN_TEAMS = 2;

    private static final Pattern CUSTOMER_WORD = Pattern.compile("\\bcustomers?\\b", Pattern.CASE_INSENSITIVE);

    public RecoverySettings {
        capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
        minTeams = minTeams <= 0 ? DEFAULT_MIN_TEAMS : minTeams;
        customers = customers == null ? List.of()
                : customers.stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
    }

    public static RecoverySettings defaults(List<String> customers) {
        return new RecoverySettings(DEFAULT_CAPACITY, DEFAULT_MIN_TEAMS, customers);
    }

    /**
     * A promise made to a customer: its recipient is someone outside the company, or its text names a
     * known customer or says "customer". A heuristic; a CRM connector would replace it.
     */
    public boolean customerFacing(Promise p, Directory directory) {
        String r = p.recipient();
        if (r != null && !r.isBlank() && !r.equals(p.owner()) && directory.person(r).isEmpty() && !directory.people().isEmpty()) {
            return true;
        }
        String what = p.what().toLowerCase(Locale.ROOT);
        for (String c : customers) {
            if (Pattern.compile("\\b" + Pattern.quote(c.toLowerCase(Locale.ROOT)) + "\\b").matcher(what).find()) {
                return true;
            }
        }
        return CUSTOMER_WORD.matcher(what).find();
    }
}
