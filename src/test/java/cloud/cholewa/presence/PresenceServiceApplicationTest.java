package cloud.cholewa.presence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PresenceServiceApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    //two scheduled tasks: with one thread a hung pass stops both (blinds-service, 2026-10-22); the pool
    //comes from cholewa-commons >= 1.11.0 (HAS-300, HAS-305)
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }
}
