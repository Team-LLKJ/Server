package kr.lit.api;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages="kr.lit")
public class LitApplication {
 public static void main(String[] args) { SpringApplication.run(LitApplication.class,args); }
}
