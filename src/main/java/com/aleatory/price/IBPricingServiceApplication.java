package com.aleatory.price;


import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Scope;

@SpringBootApplication
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@ComponentScan(basePackages = "com.aleatory.price;com.aleatory.common")
public class IBPricingServiceApplication {
    private static final Logger logger = LoggerFactory.getLogger(IBPricingServiceApplication.class);
    public static void main(String[] args) {
	logger.info("Starting Condors application at {}.", LocalDateTime.now());
	SpringApplication.run(IBPricingServiceApplication.class, args);
    }

}
