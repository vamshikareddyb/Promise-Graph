package dev.promisegraph.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.extraction.Requests;
import dev.promisegraph.gmail.GmailMapper;
import dev.promisegraph.gmail.GmailMessage;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.AbsenceDetector;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.privacy.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamModeLogicTest {

    static Message msg(String sender, String text) {
        return new Message("m1", "email:me@x.com:t1", sender, "2026-11-05T05:00:00Z", text, "demo");
    }

    @Test
    void departuresNeedLeavingTheCompanyAndADate() {
        Optional<Absence> a = AbsenceDetector.detect(msg("neha@x.com", "Hi all, my last day is 20 Nov. Thanks for everything!"));
        assertThat(a).get().satisfies(x -> {
            assertThat(x.startsOn()).isEqualTo("2026-11-21");
            assertThat(x.endsOn()).isNull();
            assertThat(x.reason()).isEqualTo(AbsenceDetector.DEPARTURE_REASON);
        });
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "I will be leaving the team on 28 Nov."))).get()
                .extracting(Absence::startsOn).isEqualTo("2026-11-29");
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "I'm leaving on Friday for Goa."))).isEmpty();
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "I'm leaving early today."))).isEmpty();
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "Neha's last day is Friday."))).isEmpty(); // not the sender's own
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "I'm leaving the company soon."))).isEmpty(); // no date
        assertThat(AbsenceDetector.detect(msg("neha@x.com", "I'm on leave from 16 Nov to 27 Nov."))).get()
                .extracting(Absence::reason).isEqualTo("leave");
    }

    @Test
    void eachMailboxGetsItsOwnMessageIdAndChannel() {
        GmailMessage m = new GmailMessage("abc", "thread9", List.of("INBOX"), 1_762_318_800_000L,
                Map.of("From", "Neha <neha@x.com>"), "I'll send the report by Friday.", null);
        Message mine = GmailMapper.toMessage(m, "demo", "me@x.com").orElseThrow();
        Message theirs = GmailMapper.toMessage(m, "demo", "you@x.com").orElseThrow();
        assertThat(mine.id()).isNotEqualTo(theirs.id());
        assertThat(mine.channel()).isEqualTo("email:me@x.com:thread9");
        assertThat(Requests.mailboxOf(mine.channel())).contains("me@x.com");
        assertThat(Requests.mailboxOf("email:thread9")).isEmpty(); // the personal setup
        assertThat(GmailMapper.toMessage(m, "demo").orElseThrow().id()).isEqualTo("gmail-abc"); // unchanged without a mailbox
    }

    static Promise promise(String id, String owner, String recipient, String channel) {
        return new Promise(id, "demo", owner, "do " + id, PromiseStatus.ACTIVE, 0.9, "t", "t", recipient, null, channel,
                List.of(new Evidence("m-" + id, "quote " + id, channel)), 1);
    }

    @Test
    void inTeamModeEveryoneSeesOnlyTheirOwn() {
        Directory dir = new Directory(List.of(
                new Member("demo", "lead@x.com", "Lead", "platform", "", true, false, true, "t").person(),
                new Member("demo", "neha@x.com", "Neha", "platform", "", false, false, true, "t").person()));
        Visibility v = Visibility.team(dir, who -> who.equals("admin@x.com"));
        List<Promise> ps = new ArrayList<>(List.of(
                promise("own", "neha@x.com", null, "email:neha@x.com:t1"),
                promise("toNeha", "friend@y.com", "neha@x.com", "email:neha@x.com:t2"),
                promise("fromHerMail", "friend@y.com", null, "email:neha@x.com:t3"),
                promise("other", "lead@x.com", null, "email:lead@x.com:t4")));
        Set<String> neha = v.visibleIds("neha@x.com", ps, List.of(), List.of(), List.of());
        assertThat(neha).containsExactlyInAnyOrder("own", "toNeha", "fromHerMail");
        // A lead doesn't see their team's promises in team mode.
        assertThat(v.visibleIds("lead@x.com", ps, List.of(), List.of(), List.of())).containsExactly("other");
        assertThat(v.canOperate("lead@x.com")).isFalse();
        assertThat(v.canOperate("admin@x.com")).isTrue();
        assertThat(v.canSeeQuotes("lead@x.com", ps.get(0))).isFalse();
    }

    @Test
    void storedSignInsAreEncrypted() {
        SecretBox box = new SecretBox(new byte[32]);
        String sealed = box.encrypt("1//refresh-token");
        assertThat(sealed).doesNotContain("refresh");
        assertThat(box.decrypt(sealed)).isEqualTo("1//refresh-token");
        assertThat(box.encrypt("1//refresh-token")).isNotEqualTo(sealed); // a fresh IV each time
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new SecretBox(other).decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void peopleWithoutATeamAreNeverEachOthersTeammates() {
        Directory dir = new Directory(List.of(
                new Member("demo", "a@x.com", "A", "", "", false, false, true, "t").person(),
                new Member("demo", "b@x.com", "B", "", "", false, false, true, "t").person()));
        assertThat(dir.teammates("a@x.com")).isEmpty();
    }
}
