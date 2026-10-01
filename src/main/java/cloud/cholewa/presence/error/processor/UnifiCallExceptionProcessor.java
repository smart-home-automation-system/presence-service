package cloud.cholewa.presence.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.presence.error.UnifiCallException;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

@Slf4j
public class UnifiCallExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        UnifiCallException unifiCallException = (UnifiCallException) throwable;

        log.error(
            "UniFi call failed ({}): {}",
            unifiCallException.getHttpStatus().value(),
            unifiCallException.getMessage()
        );

        return Errors.builder()
            .httpStatus(unifiCallException.getHttpStatus())
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("UniFi call failed")
                    .details(unifiCallException.getMessage())
                    .build()
            ))
            .build();
    }
}
