package cloud.cholewa.presence.api;

import cloud.cholewa.presence.model.PresenceReport;
import cloud.cholewa.presence.model.ResidentPresence;
import cloud.cholewa.presence.service.PresenceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/residents")
@RequiredArgsConstructor
public class ResidentController {

    private static final String LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final PresenceReportService presenceReportService;

    @GetMapping("/presence")
    Flux<ResidentPresence> getCurrentPresence() {
        return presenceReportService.getCurrentPresence();
    }

    //a resident is identified by their name, the key of the registry; from and to are local
    //date-times without an offset (2026-10-01T00:00:00), read in the zone the service runs in.
    //A pattern instead of ISO.DATE_TIME, which takes an offset as well and silently drops it
    @GetMapping("/{name}/report")
    Mono<PresenceReport> getReport(
        @PathVariable final String name,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return presenceReportService.getReport(name, from, to);
    }
}
