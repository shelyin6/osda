package com.osda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OsdaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OsdaApplication.class, args);
    }
}
