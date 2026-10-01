package cloud.cholewa.presence.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class UnifiCallException extends RuntimeException {

    private final HttpStatus httpStatus;

    public UnifiCallException(final HttpStatus httpStatus, final String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public UnifiCallException(final HttpStatus httpStatus, final String message, final Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }
}
