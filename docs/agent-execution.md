# Agent execution limits

Main clients (Telegram, web, cron and task-mode callers of the configured client) and local subagent builders use the same execution advisors around Spring AI's existing tool loop. Standalone `AgentFactory` clients use the defaults below. Each turn has its own state; concurrent conversations do not share step counters. Delegated workers inherit the parent state, so their model rounds share its limits and cancellation.

```yaml
herald:
  agent:
    execution:
      max-steps: 32
      deadline: 5m
      max-tokens: 200000
      max-cost-usd: 0
      input-usd-per-million: 0
      output-usd-per-million: 0
```

A step is one provider request, including the first request and the final synthesis. The deadline covers the whole turn, including tool execution and approvals; streaming tokens do not extend it. Disconnect/cancellation interrupts the blocking worker or cancels the stream. Tools and subsequent provider requests check cancellation before continuing. An external operation already accepted by a remote service cannot be undone; providers/tools must cooperate with interruption to release their own in-flight work immediately. A late return cannot initiate further tools or another model round.

Token totals are accumulated across provider rounds. The optional dollar cap requires positive input and output rates supplied by the operator; choose rates covering the most expensive enabled model (including failover and subagents). Cache reads use the full input rate and cache writes twice that rate for a conservative bound. They are estimates, not a billing guarantee. Cost and token checks happen after each reported provider response, so one in-flight response can cross the threshold, but its requested tools and subsequent model rounds are stopped. With a dollar cap configured, missing provider usage fails closed. Daily/monthly budget policy is also checked before each model round for all configured entry points, including the current turn’s completed usage that has not yet been persisted. Completed provider usage is finalized even if a later round fails or a cap stops the turn. Parallel turns can consume their already-approved in-flight requests before persisted totals catch up.

Limits stop with a short reason; they never expose private reasoning. Remote A2A agents retain their own server-side execution and billing controls; the local deadline bounds waiting and subsequent dispatch, but cannot retract work already accepted remotely. No second orchestration loop is introduced.
