package commerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"commerce.order", "commerce.runtime"})
public class OrderApplication {
    @org.springframework.context.annotation.Bean
    java.time.Clock orderClock() { return java.time.Clock.systemUTC(); }

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
