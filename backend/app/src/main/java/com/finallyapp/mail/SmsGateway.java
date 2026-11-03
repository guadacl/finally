package com.finallyapp.mail;

/** Sends one SMS. Implemented by {@link McpSmsGateway} (MCP tool {@code send_sms}); mocked in tests. */
public interface SmsGateway {

    /**
     * Sends a text message and returns the provider's message id.
     *
     * @param to   E.164 phone number, e.g. {@code +32470123456}
     * @param text at most 160 characters (the server truncates longer texts)
     * @throws SmsException when the server is unreachable or reports an error (blocking call, bounded by a timeout)
     */
    String sendSms(String to, String text);

    class SmsException extends RuntimeException {
        public SmsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
