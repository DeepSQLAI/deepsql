package com.dbaagent.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientContextTest {

    @Mock private HttpServletRequest request;

    @Test
    void fromRequest_picksUpAllThreeHeaders() {
        when(request.getHeader(ClientContext.HEADER_TYPE)).thenReturn("mcp");
        when(request.getHeader(ClientContext.HEADER_AGENT)).thenReturn("claude-code");
        when(request.getHeader(ClientContext.HEADER_VERSION)).thenReturn("0.13.0");

        ClientContext c = ClientContext.fromRequest(request);

        assertThat(c.clientType()).isEqualTo("mcp");
        assertThat(c.clientAgent()).isEqualTo("claude-code");
        assertThat(c.clientVersion()).isEqualTo("0.13.0");
    }

    @Test
    void fromRequest_defaultsTypeToUnknown_whenHeaderMissing() {
        // Legacy callers (e.g. the SQL Editor pre-0.13.0) don't send our
        // headers. Audit should still produce a row, with clientType=unknown.
        when(request.getHeader(ClientContext.HEADER_TYPE)).thenReturn(null);
        when(request.getHeader(ClientContext.HEADER_AGENT)).thenReturn(null);
        when(request.getHeader(ClientContext.HEADER_VERSION)).thenReturn(null);

        ClientContext c = ClientContext.fromRequest(request);

        assertThat(c.clientType()).isEqualTo("unknown");
        assertThat(c.clientAgent()).isNull();
        assertThat(c.clientVersion()).isNull();
    }

    @Test
    void fromRequest_trimsAndCapsOverlongValues() {
        when(request.getHeader(ClientContext.HEADER_TYPE)).thenReturn("  cli  ");
        when(request.getHeader(ClientContext.HEADER_AGENT)).thenReturn("a".repeat(500));
        when(request.getHeader(ClientContext.HEADER_VERSION)).thenReturn("");

        ClientContext c = ClientContext.fromRequest(request);

        assertThat(c.clientType()).isEqualTo("cli");
        assertThat(c.clientAgent()).hasSize(120);
        assertThat(c.clientVersion()).isNull(); // blank string → null
    }

    @Test
    void fromRequest_nullRequest_returnsUnknown() {
        ClientContext c = ClientContext.fromRequest(null);
        assertThat(c.clientType()).isEqualTo("unknown");
        assertThat(c.clientAgent()).isNull();
    }

    @Test
    void internal_andUnknown_andEditorWeb_factories_produceStableShapes() {
        assertThat(ClientContext.internal().clientType()).isEqualTo("internal");
        assertThat(ClientContext.unknown().clientType()).isEqualTo("unknown");
        assertThat(ClientContext.editorWeb().clientType()).isEqualTo("editor");
        assertThat(ClientContext.editorWeb().clientAgent()).isEqualTo("web");
    }
}
