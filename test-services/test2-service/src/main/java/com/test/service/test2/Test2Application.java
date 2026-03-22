package com.test.service.test2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.test.service"})
public class Test2Application {
    public static void main(String[] args) {
        SpringApplication.run(Test2Application.class, args);
    }
}
