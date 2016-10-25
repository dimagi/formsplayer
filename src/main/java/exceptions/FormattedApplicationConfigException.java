package exceptions;

/**
 * This exception is for application config exceptions that return HTML instead of text to be rendered.
 */
public class FormattedApplicationConfigException extends RuntimeException {
    public FormattedApplicationConfigException(String message) {
        super(message);
    }
}
