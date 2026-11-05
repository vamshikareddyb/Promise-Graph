package dev.promisegraph.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryTeamStore implements TeamStore {

    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, String> tokens = new LinkedHashMap<>();

    private static String key(String tenant, String email) {
        return tenant + "\u001f" + email.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public synchronized List<Member> members(String tenant) {
        return members.values().stream().filter(m -> m.tenant().equals(tenant)).toList();
    }

    @Override
    public synchronized Optional<Member> member(String tenant, String email) {
        return Optional.ofNullable(members.get(key(tenant, email)));
    }

    @Override
    public synchronized Member addIfAbsent(Member member) {
        return members.computeIfAbsent(key(member.tenant(), member.email()), k -> member);
    }

    @Override
    public synchronized void update(Member member) {
        members.put(key(member.tenant(), member.email()), member);
    }

    @Override
    public synchronized Optional<String> refreshToken(String tenant, String email) {
        return Optional.ofNullable(tokens.get(key(tenant, email)));
    }

    @Override
    public synchronized void saveRefreshToken(String tenant, String email, String encrypted, String signedInAt) {
        tokens.put(key(tenant, email), encrypted);
    }

    @Override
    public synchronized List<String> accounts(String tenant) {
        List<String> out = new ArrayList<>();
        tokens.keySet().stream().filter(k -> k.startsWith(tenant + "\u001f")).forEach(k -> out.add(k.substring(tenant.length() + 1)));
        return out;
    }
}
