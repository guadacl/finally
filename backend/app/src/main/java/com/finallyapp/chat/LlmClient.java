package com.finallyapp.chat;

import java.util.List;

/** Sends a chat transcript to an LLM and returns the raw assistant content (expected: structured JSON). */
public interface LlmClient {

    /** One message of the prompt ({@code role} = system | user | assistant). */
    record Message(String role, String content) {
    }

    /** @throws LlmException when the LLM cannot be reached or returns no content */
    String complete(List<Message> messages);

    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmException(String message) {
            super(message);
        }
    }
}
