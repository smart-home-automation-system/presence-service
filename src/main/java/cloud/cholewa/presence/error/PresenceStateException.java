package cloud.cholewa.presence.error;

//the state of the members could not be read from the database before the first pass, or not one decision
//of a pass could be written; the pass counts as skipped and the next one tries again. The message is
//fixed, never the text of the driver (HAS-476)
public class PresenceStateException extends RuntimeException {

    public PresenceStateException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
