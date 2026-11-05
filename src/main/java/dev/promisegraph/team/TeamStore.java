package dev.promisegraph.team;

import java.util.List;
import java.util.Optional;

/** People and their Google sign-ins, in team mode. */
public interface TeamStore {

    List<Member> members(String tenant);

    Optional<Member> member(String tenant, String email);

    /** Adds the person if new; an existing person keeps what admins set. Returns the stored member. */
    Member addIfAbsent(Member member);

    void update(Member member);

    /** The encrypted refresh token of a signed-in account. */
    Optional<String> refreshToken(String tenant, String email);

    void saveRefreshToken(String tenant, String email, String encrypted, String signedInAt);

    List<String> accounts(String tenant);
}
