Model reasoning spans (gemini.generate_content)
Attach these as span attributes, following the OTel GenAI semantic conventions (gen_ai.*) so any downstream tool (Jaeger, Honeycomb, Datadog) understands them without custom dashboards:
gen_ai.request.model (e.g. gemini-3.6-flash), gen_ai.operation.name = chat
gen_ai.usage.input_tokens / output_tokens — this is the single most useful field for cost/latency debugging and you have none of it right now
gen_ai.response.finish_reason (tool_calls vs stop) — this tells you why the loop continued, which is exactly the "what led to a tool_call" question you're asking
agent.iteration (you already have iterations as a local var — tag it per-span, not just as a final count on the parent)
agent.intent_id, agent.intent_confidence, agent.tools_offered_count — link the reasoning call back to the intent gate that shaped filteredTools
What you should not put in span attributes: the raw prompt or raw completion text, and especially not toolCall.thoughtSignature() verbatim. That's Gemini's internal reasoning blob — treat it as an opaque token for replay/audit (store it in AssistantMessage, which you already do), but never surface it in tracing backends. It's often large, can leak system-prompt content, and most tracing backends have span tag size limits you'll blow past.
If you genuinely need reasoning content for debugging, log it — not trace it — to a separate, access-controlled structured log stream with sampling (e.g. 1% of turns), not as a span tag that every engineer with trace access can read.
mcp.tool_call spans
Right now this span only wraps the HTTP hop. Add attributes that answer "why was this tool allowed to run":
gen_ai.tool.name = toolCall.name()
agent.tool.validation_result — from your intentToolRegistry.isValid(...) check
agent.policy.decision + agent.policy.reason + agent.policy.required_scope — this is gold for audit; you already compute all of it in policyEngine.authorize(...), it just isn't attached to the span
agent.tool.result_size_bytes or a truncated/hashed result — not the full result text
error tag when toolResult indicates failure
What NOT to record: full toolCall.arguments() unredacted. Orders, customer IDs, addresses — anything PII/PCI-adjacent — should be redacted or hashed before it becomes a span tag, because traces usually have looser retention/access policies than your primary DB, and once it's in a trace it's very hard to purge.
Concretely, in your code
`ExternalMcpHub.handleToolCalls(...)` is the natural execution chokepoint — it evaluates intent validation, policy authorization, and dispatches tool calls to `executeTool(...)` while recording `agent.tool.call` / `agent.tool.result` events directly on the active span, avoiding bespoke decision recorder abstractions and keeping traces self-describing without a second system of record.
One structural note: your recordEvent(span, "model.request") / "model.response") events currently carry no payload at all — they're just timestamps. Consider promoting agent.tool.call / agent.tool.result similarly to carry the tool name as an event attribute (not just a bare event marker), so when someone's staring at a flame graph they don't have to cross-reference logs to know which tool ran in that 58ms list_customer_orders span two levels down.
