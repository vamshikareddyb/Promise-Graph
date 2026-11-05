package dev.promisegraph.team;

import dev.promisegraph.domain.Person;
import java.util.Locale;

/**
 * A person in team mode, identified by their email address.
 *
 * @param team empty until an admin sets it; people without a team can't be suggested as cover
 * @param lead leads approve handovers when someone in their team leaves
 * @param admin admins edit the People page and see the operations pages
 * @param active false once someone has left: they are no longer suggested as cover
 */
public record Member(String tenant, String email, String name, String team, String role, boolean lead, boolean admin,
        boolean active, String createdAt) {

    public Member {
        email = email.strip().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? email : name.strip();
        team = team == null ? "" : team.strip();
        role = role == null ? "" : role.strip();
    }

    public Person person() {
        return new Person(email, name, team.isEmpty() ? "no team: " + email : team, role, lead);
    }

    public Member edited(String newName, String newTeam, String newRole, boolean newLead, boolean newAdmin, boolean newActive) {
        return new Member(tenant, email, newName, newTeam, newRole, newLead, newAdmin, newActive, createdAt);
    }
}
