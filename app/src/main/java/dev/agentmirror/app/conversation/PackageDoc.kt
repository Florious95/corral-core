/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
/**
 * Persistent-socket conversation_v1 client: Pi/Grok share the negotiated hub and bounded
 * stream reducer. ConversationCodec also supplies the envelope-head classifier consumed by
 * the typed TUI input transport. Neither client opens a second connection or forks Core.
 */
package dev.agentmirror.app.conversation
