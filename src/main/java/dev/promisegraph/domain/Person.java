package dev.promisegraph.domain;

public record Person(String id, String name, String team, String role, boolean lead, String timezone) {

    public Person(String id, String name, String team, String role, boolean lead) {
        this(id, name, team, role, lead, "Asia/Kolkata");
    }
}
