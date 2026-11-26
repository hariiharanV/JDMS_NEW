package com.jmr.jdms.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.search.domain.SearchService.Segment;
import org.junit.jupiter.api.Test;

/** Highlighted fragments become text segments, also when OpenSearch's trim has cut a marker at an edge. */
class SnippetSegmentsTest {

    static final String S = SearchService.MATCH_START;
    static final String E = SearchService.MATCH_END;

    @Test
    void matchesInsideTheFragment() {
        assertThat(SearchService.segments("data page - Aisha " + S + "Al" + E + " " + S + "Balushi" + E + " - C1"))
                .containsExactly(new Segment("data page - Aisha ", false), new Segment("Al", true),
                        new Segment(" ", false), new Segment("Balushi", true), new Segment(" - C1", false));
    }

    @Test
    void aMatchAtTheStartWhoseMarkerWasTrimmed() {
        assertThat(SearchService.segments("Passport" + E + " data page"))
                .containsExactly(new Segment("Passport", true), new Segment(" data page", false));
    }

    @Test
    void aMatchAtTheEndWhoseMarkerWasTrimmed() {
        assertThat(SearchService.segments("data page " + S + "Passport"))
                .containsExactly(new Segment("data page ", false), new Segment("Passport", true));
    }

    @Test
    void noMatchAtAll() {
        assertThat(SearchService.segments("plain text")).containsExactly(new Segment("plain text", false));
        assertThat(SearchService.segments("")).isEmpty();
    }
}
