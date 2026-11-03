package com.finallyapp.mail;

/** Sends one email. Implemented by {@link McpEmailGateway} (MCP tool {@code send_email}); mocked in tests. */
public interface EmailGateway {

    /**
     * Sends a plain-text email and returns the SMTP message id reported by the server.
     *
     * @throws EmailException when the server is unreachable or reports an error (blocking call, bounded by a timeout)
     */
    String sendEmail(String to, String subject, String text);

    class EmailException extends RuntimeException {
        public EmailException(String message) {
            super(message);
        }

        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
