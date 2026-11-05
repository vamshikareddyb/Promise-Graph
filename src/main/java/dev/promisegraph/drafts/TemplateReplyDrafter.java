package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.PromiseNote;
import dev.promisegraph.drafts.DraftContracts.Written;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * A plain acknowledgement built from the tracked promises, with no model. Used when Claude is not
 * configured, and by the workflow when Claude stays unavailable. Deterministic, so it is safe to call
 * from workflow code.
 */
public final class TemplateReplyDrafter implements ReplyDrafter {

    public static final String BY = "template";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);

    @Override
    public Written write(DraftContext context) {
        return new Written(compose(context), BY);
    }

    public static String compose(DraftContext ctx) {
        StringBuilder out = new StringBuilder();
        String first = firstName(ctx.replyTo());
        out.append(first == null ? "Hi," : "Hi " + first + ",");
        if (ctx.focus().isPresent()) {
            PromiseNote p = ctx.focus().get();
            boolean mine = p.owner() != null && p.owner().equalsIgnoreCase(ctx.owner());
            String due = p.due() == null ? "" : " by " + day(p.due());
            out.append("\n\n").append(mine
                    ? "A quick update: I'm on track to " + lower(p.what()) + due + ". [Add any detail here.]"
                    : "Just checking in on " + planTo(p.what()) + due + ". Is it still on track?");
            return out.append("\n\nThanks,").toString();
        }
        out.append("\n\nThanks for your email.");
        List<PromiseNote> here = ctx.promises().stream().filter(PromiseNote::thisThread).toList();
        for (PromiseNote p : here) {
            boolean mine = p.owner() != null && p.owner().equalsIgnoreCase(ctx.owner());
            String due = p.due() == null ? "" : " by " + day(p.due());
            out.append("\n\n").append(mine
                    ? "Confirming I'll " + lower(p.what()) + due + "."
                    : "Noted that you'll " + lower(p.what()) + due + " - thank you.");
        }
        if (here.isEmpty()) {
            out.append("\n\n[Your reply]");
        }
        return out.append("\n\nBest,").toString();
    }

    /** "neha.kapoor@example.com" -> "Neha"; null when the address gives no usable name. */
    static String firstName(String address) {
        if (address == null || !address.contains("@")) {
            return null;
        }
        String local = address.substring(0, address.indexOf('@')).split("[._+-]")[0].replaceAll("\\d", "");
        if (local.length() < 2) {
            return null;
        }
        return Character.toUpperCase(local.charAt(0)) + local.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String lower(String what) {
        if (what == null || what.isBlank()) {
            return "follow up";
        }
        String w = what.strip().replaceAll("[.!]+$", "");
        return w.length() > 1 && Character.isUpperCase(w.charAt(0)) && Character.isLowerCase(w.charAt(1))
                ? Character.toLowerCase(w.charAt(0)) + w.substring(1) : w;
    }

    /** "send the deck" -> "your plan to send the deck". */
    private static String planTo(String what) {
        return "your plan to " + lower(what);
    }

    private static String day(String iso) {
        try {
            return LocalDate.parse(iso).format(DAY);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }
}
