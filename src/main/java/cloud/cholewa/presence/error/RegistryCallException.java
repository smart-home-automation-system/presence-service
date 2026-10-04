package cloud.cholewa.presence.error;

//a failed read of the household registry in database-service. The detection pass absorbs it and
//falls back to the last registry it read; the reporting API answers it as a 502
public class RegistryCallException extends RuntimeException {

    public RegistryCallException(final String message) {
        super(message);
    }

    public RegistryCallException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
