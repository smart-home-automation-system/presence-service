package cloud.cholewa.presence.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

//the registry is behind this service, so its failure is a bad gateway for the caller. The answer
//is a fixed message: this API is routed through the gateway, and the exception names the service
//behind it, the failure class and the configured timeout - that goes to the log only
@Slf4j
public class RegistryCallExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        log.error("Household registry call failed: {}", throwable.getMessage());

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_GATEWAY)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Household registry unavailable")
                    .build()
            ))
            .build();
    }
}
