package dev.promisegraph.recovery;

import dev.promisegraph.domain.Promise;

/**
 * One change a recovery plan makes to one promise.
 *
 * <ul>
 *   <li>{@code move_date}: the promise gets a new due date ({@code toDue}).
 *   <li>{@code keep_date}: the owner keeps their date; their upstream work must still finish first.
 *   <li>{@code deliver_partial}: the owner keeps their date by delivering the part that doesn't wait
 *       on the late work first; the rest follows.
 *   <li>{@code add_helper}: {@code person} helps the owner, so the work can finish sooner.
 *   <li>{@code reassign}: {@code person} takes the promise over.
 * </ul>
 *
 * @param what, owner, fromDue the promise as it was when the plan was made, shown to approvers and
 *     used to undo the move
 */
public record Move(String kind, String promiseId, String what, String owner, String fromDue, String toDue, String person, String note) {

    public static final String MOVE_DATE = "move_date";
    public static final String KEEP_DATE = "keep_date";
    public static final String DELIVER_PARTIAL = "deliver_partial";
    public static final String ADD_HELPER = "add_helper";
    public static final String REASSIGN = "reassign";

    public static boolean knownKind(String kind) {
        return MOVE_DATE.equals(kind) || KEEP_DATE.equals(kind) || DELIVER_PARTIAL.equals(kind)
                || ADD_HELPER.equals(kind) || REASSIGN.equals(kind);
    }

    /** Factories rather than extra constructors, so Jackson has one way to build this record. */
    public static Move moveDate(Promise p, String toDue, String note) {
        return new Move(MOVE_DATE, p.id(), p.what(), p.owner(), p.due(), toDue, null, note);
    }

    public static Move keepDate(Promise p, String note) {
        return new Move(KEEP_DATE, p.id(), p.what(), p.owner(), p.due(), p.due(), null, note);
    }

    public static Move deliverPartial(Promise p, String note) {
        return new Move(DELIVER_PARTIAL, p.id(), p.what(), p.owner(), p.due(), p.due(), null, note);
    }

    public static Move addHelper(Promise p, String person, String note) {
        return new Move(ADD_HELPER, p.id(), p.what(), p.owner(), p.due(), p.due(), person, note);
    }

    public static Move reassign(Promise p, String person, String note) {
        return new Move(REASSIGN, p.id(), p.what(), p.owner(), p.due(), p.due(), person, note);
    }

    /** Changes the promise's date. */
    public boolean changesDate() {
        return MOVE_DATE.equals(kind);
    }

    /** Gives someone extra work. */
    public boolean addsWork() {
        return ADD_HELPER.equals(kind) || REASSIGN.equals(kind);
    }

    /** Identifies the move regardless of wording, so the same plan is not offered twice. */
    public String signature() {
        return kind + "|" + promiseId + "|" + (changesDate() ? toDue : "") + "|" + (addsWork() ? person : "");
    }

    /** One line for people, e.g. "Dev: build the export ingestion job 18 Nov → 2 Dec". */
    public String describe() {
        String due = fromDue == null ? "no date" : fromDue;
        return switch (kind) {
            case MOVE_DATE -> owner + ": " + what + " " + due + " → " + toDue;
            case KEEP_DATE -> owner + ": " + what + " keeps " + due;
            case DELIVER_PARTIAL -> owner + ": " + what + " keeps " + due + " with a first part";
            case ADD_HELPER -> person + " helps " + owner + " with " + what;
            case REASSIGN -> what + " moves from " + owner + " to " + person;
            default -> kind + " " + what;
        };
    }
}
