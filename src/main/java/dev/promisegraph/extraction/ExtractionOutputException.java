package dev.promisegraph.extraction;

/** The model returned something unusable. Retrying the call may fix it. */
public class ExtractionOutputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionOutputException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExtractionOutputException(String message) {
        super(message);
    }
}
