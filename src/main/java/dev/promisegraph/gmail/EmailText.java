package dev.promisegraph.gmail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reduces an email to the words its sender actually wrote: no quoted replies, no forwarded
 * history, no signature, no HTML. Promises in older quoted text belong to older messages.
 */
public final class EmailText {

    public static final int MAX_LENGTH = 4000;

    private static final Pattern ON_WROTE = Pattern.compile("^On .{4,200}wrote:\\s*$");
    private static final Pattern ORIGINAL = Pattern.compile("^-{2,}\\s*(Original Message|Forwarded message)\\s*-{2,}.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTLOOK_HEADER = Pattern.compile("^From:\\s.+$");
    private static final Pattern SIGNATURE = Pattern.compile("^(--|__)\\s*$");
    private static final Pattern SENT_FROM = Pattern.compile("^Sent from my .+$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BLOCK_TAGS = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>");
    private static final Pattern HIDDEN = Pattern.compile("(?is)<(style|script|head)\\b.*?</\\1\\s*>");
    private static final Pattern QUOTE_BLOCK = Pattern.compile("(?is)<blockquote\\b.*?</blockquote\\s*>");
    private static final Pattern GMAIL_QUOTE = Pattern.compile("(?is)<div[^>]*class=\"gmail_quote\".*$");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private EmailText() {}

    /** The sender's own text, preferring the plain-text part. Empty if there is none. */
    public static String clean(String plainText, String html) {
        String text = plainText != null && !plainText.isBlank() ? plainText : htmlToText(html);
        if (text == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.strip();
            if (ON_WROTE.matcher(line).matches() || ORIGINAL.matcher(line).matches()
                    || SIGNATURE.matcher(line).matches() || SENT_FROM.matcher(line).matches()) {
                break;
            }
            if (OUTLOOK_HEADER.matcher(line).matches() && !kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
                break; // "From: ..." after a blank line starts a quoted Outlook reply
            }
            if (line.startsWith(">")) {
                continue;
            }
            kept.add(line);
        }
        String joined = String.join("\n", kept).replaceAll("\n{3,}", "\n\n").strip();
        return joined.length() > MAX_LENGTH ? joined.substring(0, MAX_LENGTH) : joined;
    }

    static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String s = GMAIL_QUOTE.matcher(html).replaceAll("");
        s = HIDDEN.matcher(s).replaceAll("");
        s = QUOTE_BLOCK.matcher(s).replaceAll("");
        s = BLOCK_TAGS.matcher(s).replaceAll("\n");
        s = TAGS.matcher(s).replaceAll("");
        // &amp; last, so "&amp;lt;" becomes the text "&lt;" rather than "<".
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&rsquo;", "’").replace("&amp;", "&");
        return s.isBlank() ? null : s;
    }
}
