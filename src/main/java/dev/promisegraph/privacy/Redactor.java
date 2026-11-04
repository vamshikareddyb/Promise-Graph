package dev.promisegraph.privacy;

import dev.promisegraph.domain.Message;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes details that should never be stored, logged or sent to a model: email addresses (the
 * domain is kept, because it can show a message is about a customer), phone numbers, card-like
 * numbers, and things that look like passwords or API keys. Applied to every message before it
 * enters a workflow, so none of it reaches workflow history, the database or Claude.
 */
public final class Redactor {

    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");
    private static final Pattern SECRET_LABELLED = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|passcode|otp|pin|api[ _-]?key|secret|token)(\\s*(?:is|:|=)\\s*)\\S+");
    private static final Pattern SECRET_SHAPED = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|xox[abpr]-[A-Za-z0-9-]{10,})\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w-])\\+?\\d[\\d ()-]{8,}\\d\\b");

    private Redactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = SECRET_LABELLED.matcher(text).replaceAll("$1$2[secret]");
        out = SECRET_SHAPED.matcher(out).replaceAll("[secret]");
        Matcher m = EMAIL.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("[email at " + m.group(1) + "]"));
        }
        m.appendTail(sb);
        out = CARD.matcher(sb.toString()).replaceAll("[card number]");
        out = PHONE.matcher(out).replaceAll(r -> looksLikeDate(r.group()) ? Matcher.quoteReplacement(r.group()) : "[phone]");
        return out;
    }

    /** "2026-11-20" and "20-11-2026" are dates, not phone numbers. */
    private static boolean looksLikeDate(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}") || s.matches("\\d{2}-\\d{2}-\\d{4}");
    }

    public static Message redact(Message m) {
        String text = redact(m.text());
        return text.equals(m.text()) ? m : new Message(m.id(), m.channel(), m.sender(), m.ts(), text, m.tenant());
    }
}
