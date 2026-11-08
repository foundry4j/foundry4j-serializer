package io.github.foundry4j.serializer;

/// Base exception for every failure raised by the serializer.
///
/// The exception is unchecked so that codecs stay usable in lambdas and stream pipelines.
/// Typical causes are unsupported types, malformed input, exhausted limits, or a checksum
/// mismatch.
public class SerializationException extends RuntimeException {

    /// Creates an exception with a descriptive message.
    ///
    /// @param message the detail message
    public SerializationException(String message) {
        super(message);
    }

    /// Creates an exception with a descriptive message and an underlying cause.
    ///
    /// @param message the detail message
    /// @param cause   the exception that triggered this failure
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
