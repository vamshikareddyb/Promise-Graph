package dev.promisegraph.store;

import dev.promisegraph.links.Link;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Dependency links between promises. */
public interface LinkStore {

    /** Insert if absent. True if this call created the link. */
    boolean addLink(Link link);

    Optional<Link> getLink(String tenant, String from, String to);

    /** Records a person's decision; a rejected link is never suggested again. */
    boolean decide(String tenant, String from, String to, Link.Status status, String by);

    List<Link> links(String tenant);

    /** Links touching any of these promises, either direction. */
    List<Link> linksOf(String tenant, Collection<String> promiseIds);

    /** Confirmed links whose upstream is one of these promises. */
    List<Link> confirmedFrom(String tenant, Collection<String> upstreamIds);
}
