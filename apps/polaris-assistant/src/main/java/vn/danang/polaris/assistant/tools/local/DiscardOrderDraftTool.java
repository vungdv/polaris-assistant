package vn.danang.polaris.assistant.tools.local;

import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.web.exception.DraftConflictException;
import vn.danang.polaris.assistant.service.OrderDraftService;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.LocalTool;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * {@code discard_order_draft} (BPMN {@code A_EndDeclined}): the shopper no longer wants the staged draft,
 * so it moves to {@code CANCELLED}. Safe for the model to call because it changes no domain data: no order
 * exists yet and no stock was reserved.
 */
@Component
public class DiscardOrderDraftTool implements LocalTool {

    public static final String NAME = "discard_order_draft";

    private static final String INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {}
        }
        """;

    private final OrderDraftService orderDraftService;
    private final UserContext userContext;
    private final Tool definition;

    @Autowired
    public DiscardOrderDraftTool(OrderDraftService orderDraftService, UserContext userContext, ObjectMapper objectMapper) {
        this.orderDraftService = Objects.requireNonNull(orderDraftService, "orderDraftService must not be null");
        this.userContext = Objects.requireNonNull(userContext, "userContext must not be null");
        this.definition = Tool.builder(NAME, new JacksonMcpJsonMapper(objectMapper), INPUT_SCHEMA)
                .description("Discard the shopper's open order draft when they decline it or no longer want it. "
                        + "Nothing is ordered; to change a draft, call stage_order_draft again instead.")
                .build();
    }

    @Override
    public Tool definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
        Optional<SignedInCaller> signedIn = SignedInCaller.resolve(context, userContext);
        if (signedIn.isEmpty()) {
            return ToolResult.denied(toolCall, "Sign in to manage order drafts: anonymous callers have no drafts.");
        }
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error(toolCall, "An order draft needs a chat session.");
        }

        Optional<OrderDraft> discarded;
        try {
            discarded = orderDraftService.discardOpenDraft(sessionId, signedIn.get().userId());
        } catch (SessionAccessDeniedException e) {
            return ToolResult.denied(toolCall, e.getMessage());
        } catch (DraftConflictException e) {
            return ToolResult.error(toolCall, "The order draft was changed at the same time by another request; please check it again.");
        } catch (IllegalStateException e) {
            return ToolResult.error(toolCall, "Could not discard the order draft: " + e.getMessage());
        }

        // Either way no draft is open any more, so a draft card staged earlier in this turn is stale.
        if (discarded.isEmpty()) {
            return ToolResult.success(toolCall, "There is no open order draft to discard. Nothing was ordered.")
                    .retractingWidget(ChatWidget.ORDER_DRAFT);
        }
        OrderDraft draft = discarded.get();
        if (draft.getStatus() == DraftStatus.EXPIRED) {
            return ToolResult.success(toolCall, "Order draft " + draft.getId() + " had already expired and is closed. Nothing was ordered.")
                    .retractingWidget(ChatWidget.ORDER_DRAFT);
        }
        return ToolResult.success(toolCall, "Order draft " + draft.getId() + " discarded. Nothing was ordered.")
                .retractingWidget(ChatWidget.ORDER_DRAFT);
    }
}
