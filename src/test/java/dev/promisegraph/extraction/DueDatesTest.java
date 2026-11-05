package dev.promisegraph.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DueDatesTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    @ParameterizedTest(name = "{0} said on {1}")
    @CsvSource({
            "send it by Friday,    2026-11-02, 2026-11-06",
            "by Friday,            2026-11-06, 2026-11-06",
            "by Monday,            2026-11-06, 2026-11-09",
            "next Friday,          2026-11-02, 2026-11-13",
            "tomorrow,             2026-11-02, 2026-11-03",
            "by EOD,               2026-11-02, 2026-11-02",
            "end of the week,      2026-11-02, 2026-11-06",
            "next week,            2026-11-02, 2026-11-13",
            "end of the month,     2026-11-02, 2026-11-30",
            "by 13 Nov,            2026-11-02, 2026-11-13",
            "by Nov 20,            2026-11-02, 2026-11-20",
            "by 3rd December,      2026-11-02, 2026-12-03",
            "by 2026-12-01,        2026-11-02, 2026-12-01",
            "by 5 Jan,             2026-12-20, 2027-01-05",
            "in two weeks,         2026-11-02, 2026-11-16",
            "in 3 days,            2026-11-02, 2026-11-05",
    })
    void parsesDueDates(String text, LocalDate sent, LocalDate expected) {
        assertThat(DueDates.parseDue(text, sent)).contains(expected);
    }

    @Test
    void prefersDateAfterDeadlineWord() {
        assertThat(DueDates.parseDue("After the 4 Nov review I'll send it by 10 Nov", MONDAY))
                .contains(LocalDate.of(2026, 11, 10));
    }

    @Test
    void noDateAndInvalidDate() {
        assertThat(DueDates.parseDue("I'll take a look", MONDAY)).isEmpty();
        assertThat(DueDates.parseDue("by 31 Nov", MONDAY)).isEmpty();
    }

    @Test
    void shiftDays() {
        assertThat(DueDates.parseShiftDays("The API will slip by two weeks")).contains(14);
        assertThat(DueDates.parseShiftDays("this is delayed by 3 days")).contains(3);
        assertThat(DueDates.parseShiftDays("pushed back by a week")).contains(7);
        assertThat(DueDates.parseShiftDays("will slip to Friday")).isEmpty();
    }

    @Test
    void numericDatesAreDayFirst() {
        assertThat(DueDates.parseDue("by 07/11/2026", MONDAY)).contains(LocalDate.of(2026, 11, 7));
        assertThat(DueDates.parseDue("on 07.11.2026", MONDAY)).contains(LocalDate.of(2026, 11, 7));
        assertThat(DueDates.parseDue("by 7-11-26", MONDAY)).contains(LocalDate.of(2026, 11, 7));
        assertThat(DueDates.parseDue("by 11/25/2026", MONDAY)).contains(LocalDate.of(2026, 11, 25)); // can only be month first
        assertThat(DueDates.parseDue("by 12/11", MONDAY)).contains(LocalDate.of(2026, 11, 12));
        assertThat(DueDates.parseDue("3/4 of the team agreed", MONDAY)).isEmpty();
        assertThat(DueDates.parseDue("by 31/02/2026", MONDAY)).isEmpty();
    }
}
