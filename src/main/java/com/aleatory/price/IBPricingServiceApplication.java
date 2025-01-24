package com.aleatory.price;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Scope;

import com.aleatory.common.app.RestartableApplication;

@SpringBootApplication
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@ComponentScan(basePackages = "com.aleatory.price;com.aleatory.common")
public class IBPricingServiceApplication extends RestartableApplication {
    private static final Logger logger = LoggerFactory.getLogger(IBPricingServiceApplication.class);

    public static void main(String[] args) {
        logger.info("Starting Condors application at {}.", LocalDateTime.now());
        context = SpringApplication.run(IBPricingServiceApplication.class, args);
        scheduleRestart(IBPricingServiceApplication.class);
    }

}
