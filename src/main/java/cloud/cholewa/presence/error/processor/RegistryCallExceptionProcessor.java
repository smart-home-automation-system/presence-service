package cloud.cholewa.presence.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

//the registry is behind this service, so its failure is a bad gateway for the caller; the message
//is built in HouseholdClient and never carries the body database-service answered with
@Slf4j
public class RegistryCallExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        log.error("Household registry call failed: {}", throwable.getMessage());

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_GATEWAY)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Household registry call failed")
                    .details(throwable.getMessage())
                    .build()
            ))
            .build();
    }
}
