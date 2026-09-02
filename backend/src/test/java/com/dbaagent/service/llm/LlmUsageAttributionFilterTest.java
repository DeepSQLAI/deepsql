package com.dbaagent.service.llm;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmUsageAttributionFilterTest {

    private final LlmUsageAttributionFilter filter = new LlmUsageAttributionFilter();

    /** Captures what the context looked like from inside the chain. */
    private LlmUsageContext.Scope scopeSeenBy(String method, String uri) throws Exception {
        List<LlmUsageContext.Scope> seen = new ArrayList<>();
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.add(LlmUsageContext.current()));
        return seen.get(0);
    }

    @Test
    void labelsChatRequests() throws Exception {
        assertThat(scopeSeenBy("POST", "/api/chat/ask").feature()).isEqualTo("chat");
    }

    /**
     * The longest prefix must win. Ordinary map iteration would let {@code /api/dashboards}
     * claim this and collapse the most expensive feature in the product into the generic
     * bucket.
     */
    @Test
    void prefersTheMoreSpecificPrefix() throws Exception {
        assertThat(scopeSeenBy("POST", "/api/dashboards/generate/stream").feature())
                .isEqualTo("dashboard-generate");
        assertThat(scopeSeenBy("GET", "/api/dashboards/123").feature())
                .isEqualTo("dashboard");
    }

    @Test
    void extractsTheConnectionIdFromThePath() throws Exception {
        assertThat(scopeSeenBy("GET", "/api/brain/notes/connection/conn-42").connectionId())
                .isEqualTo("conn-42");
    }

    @Test
    void extractsTheConnectionIdFromAQueryParameter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/ask");
        request.setRequestURI("/api/chat/ask");
        request.setParameter("connectionId", "conn-9");

        List<String> seen = new ArrayList<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.add(LlmUsageContext.currentConnectionId()));

        assertThat(seen).containsExactly("conn-9");
    }

    /** "test" is an action, not an id; labelling it as one invents a connection. */
    @Test
    void doesNotMistakeAnActionSegmentForAConnectionId() throws Exception {
        assertThat(scopeSeenBy("POST", "/api/connections/test").connectionId()).isNull();
    }

    @Test
    void unmappedPathsRecordUnknownRatherThanFailing() throws Exception {
        assertThat(scopeSeenBy("GET", "/api/some-new-feature").feature())
                .isEqualTo(LlmUsageContext.UNKNOWN_FEATURE);
    }

    /** The context must not leak into whatever the thread handles next. */
    @Test
    void clearsTheContextAfterTheRequest() throws Exception {
        scopeSeenBy("POST", "/api/chat/ask");

        assertThat(LlmUsageContext.current()).isNull();
    }

    @Test
    void clearsTheContextEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/ask");
        request.setRequestURI("/api/chat/ask");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("handler blew up");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(LlmUsageContext.current()).isNull();
    }

    /**
     * A checked exception from the chain must reach the container as itself. The context
     * is carried through a {@code Supplier}, which cannot throw one, so it is wrapped and
     * must be unwrapped again — a bug here would turn every servlet error into an opaque
     * runtime failure.
     */
    @Test
    void propagatesCheckedExceptionsUnwrapped() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/ask");
        request.setRequestURI("/api/chat/ask");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new java.io.IOException("socket closed");
                }))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("socket closed");
    }
}
