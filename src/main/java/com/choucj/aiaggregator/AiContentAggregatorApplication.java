package com.choucj.aiaggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.choucj.aiaggregator.common")
public class AiContentAggregatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiContentAggregatorApplication.class, args);
	}

}
