package vn.danang.polaris.assistant.tools.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.assistant.service.OrderDraftService;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Unit tests for {@link DiscardOrderDraftTool}: signed-in callers discard their session's open draft;
 * anonymous callers are refused.
 */
class DiscardOrderDraftToolTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final ToolCall CALL = new ToolCall(DiscardOrderDraftTool.NAME, Map.of());

    private OrderDraftService draftService;
    private DiscardOrderDraftTool tool;

    @BeforeEach
    void setUp() {
        draftService = mock(OrderDraftService.class);
        tool = new DiscardOrderDraftTool(draftService, new UserContext(), new ObjectMapper());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "alice").build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ToolExecutionContext ctx(String userId) {
        return new ToolExecutionContext("sess-1", userId, 1);
    }

    @Test
    @DisplayName("Given an open draft, when discarded, then it is cancelled and the model is told nothing was ordered")
    void discards_open_draft() {
        OrderDraft draft = OrderDraft.stage("sess-1", 7L,
                List.of(DraftLine.of("NG-CHARGER-01", "Charger", 1, new BigDecimal("24.90"))), OrderDraft.DEFAULT_TTL, NOW);
        draft.cancel(NOW);
        when(draftService.discardOpenDraft("sess-1", "alice")).thenReturn(Optional.of(draft));

        ToolResult result = tool.execute(CALL, ctx("alice"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.result()).contains(draft.getId()).contains("discarded").contains("Nothing was ordered");
        assertThat(result.retractsWidget()).isEqualTo("ORDER_DRAFT");
    }

    @Test
    @DisplayName("Given no open draft, when discarded, then success with nothing to discard")
    void nothing_to_discard() {
        when(draftService.discardOpenDraft("sess-1", "alice")).thenReturn(Optional.empty());

        ToolResult result = tool.execute(CALL, ctx("alice"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.result()).contains("no open order draft");
        assertThat(result.retractsWidget()).isEqualTo("ORDER_DRAFT");
    }

    @Test
    @DisplayName("Given another user's session, when discarded, then denied")
    void other_users_session_is_denied() {
        when(draftService.discardOpenDraft("sess-1", "alice")).thenThrow(new SessionAccessDeniedException("sess-1"));

        assertThat(tool.execute(CALL, ctx("alice")).isDenied()).isTrue();
    }

    @Test
    @DisplayName("Given an anonymous caller, when discarded, then denied without touching drafts")
    void anonymous_is_refused() {
        SecurityContextHolder.clearContext();

        assertThat(tool.execute(CALL, ctx("anonymous")).isDenied()).isTrue();
        verifyNoInteractions(draftService);
    }
}
