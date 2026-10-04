package cloud.cholewa.presence.config;

import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.error.processor.RegistryCallExceptionProcessor;
import cloud.cholewa.presence.error.processor.UnifiCallExceptionProcessor;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

import java.util.Map;

@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    GlobalErrorExceptionHandler globalExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        GlobalErrorExceptionHandler globalErrorExceptionHandler = new GlobalErrorExceptionHandler(
            errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer);

        globalErrorExceptionHandler.withCustomErrorProcessor(Map.of(
            UnifiCallException.class, new UnifiCallExceptionProcessor(),
            RegistryCallException.class, new RegistryCallExceptionProcessor()
        ));

        return globalErrorExceptionHandler;
    }
}
