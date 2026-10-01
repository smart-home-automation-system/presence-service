package cloud.cholewa.presence.error;

//a failed read of the household registry in database-service; it never reaches an HTTP response -
//the detection pass falls back to the last registry it read
public class RegistryCallException extends RuntimeException {

    public RegistryCallException(final String message) {
        super(message);
    }

    public RegistryCallException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
