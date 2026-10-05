package cloud.cholewa.presence.api;

import cloud.cholewa.presence.model.HouseReport;
import cloud.cholewa.presence.service.PresenceStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//"house", not "home": under the base path /home/presence a /home/report would read /home/presence/home/report
@RestController
@RequestMapping("/house")
@RequiredArgsConstructor
public class HouseController {

    private final PresenceStatisticsService presenceStatisticsService;

    @GetMapping("/report")
    Mono<HouseReport> getReport(
        @RequestParam @DateTimeFormat(pattern = ResidentController.LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = ResidentController.LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return presenceStatisticsService.getHouseReport(from, to);
    }
}
