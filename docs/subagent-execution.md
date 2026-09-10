# Subagent execution

Herald uses upstream TaskTool and TaskOutputTool for delegation and result collection. Role-specific workers keep separate prompts and allowed tool callbacks. The supervisor receives collected results and can synthesize partial success when a worker fails.

Agent-utils 0.12.0 currently passes a list of callbacks to `defaultTools`, which Spring AI interprets as annotated Java objects. `SubagentToolCallbackCompatibility` translates that specific call to `defaultToolCallbacks` while retaining the upstream role filtering, executor, model selection and skill loading. Remove the adapter when the upstream implementation uses the callback API correctly.

`OwnedTaskRepository` retains the upstream BackgroundTask interface while associating every background worker with its originating execution state. TaskOutput cannot retrieve another turn's tasks. Workers retain the channel/conversation context and share the parent's execution budget and deadline. Parent cancellation interrupts their actual FutureTask, rather than only cancelling an unrelated CompletableFuture. Finished results remain available during the parent turn and are released when that turn ends. These are subordinate tasks, not detached jobs that survive a completed supervisor turn.

TaskOutput's own short wait timeout can report a still-running worker without destroying it, so the supervisor can collect other results. The overall execution deadline and explicit cancellation remain the outer bounds. As with Java cancellation generally, a third-party operation that ignores interruption may run until its own timeout; subsequent guarded model/tool work is rejected.

The deterministic orchestration fixture runs the actual upstream TaskTool, ClaudeSubagentExecutor and TaskOutputTool with two concurrent role-specific models, exercises a real callback per role, and verifies synthesis for success and one-worker failure. Additional coverage verifies wait timeout, originating-context propagation, task ownership and parent cancellation of a blocked worker.
