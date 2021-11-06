package finance.universe.backtest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Slf4j
@EnableJpaRepositories()
@SpringBootApplication
public class BackTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackTestApplication.class);
        log.info("open your browser and visit http://localhost:9989/index.html");
    }

}
