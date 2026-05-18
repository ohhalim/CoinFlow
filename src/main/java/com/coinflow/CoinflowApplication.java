package com.coinflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CoinflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoinflowApplication.class, args);
	}

}
