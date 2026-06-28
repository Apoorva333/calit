package site.asm0dey.calit.email;

/**
 * iTIP METHOD for a calendar event: an invitation/update ({@link #REQUEST}) or a
 * cancellation ({@link #CANCEL}). The enum name is written verbatim into the .ics
 * {@code METHOD:} line, so a closed, type-safe set also rules out injection.
 */
public enum IcsMethod {
    REQUEST,
    CANCEL
}
