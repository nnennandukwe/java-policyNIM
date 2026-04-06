package io.policynim.policy.parse;

public class InvalidPolicyDocumentException extends RuntimeException {

    public InvalidPolicyDocumentException(String message) {
        super(message);
    }

    public InvalidPolicyDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
