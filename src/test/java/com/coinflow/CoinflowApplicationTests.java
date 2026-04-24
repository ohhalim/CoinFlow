package com.coinflow;

import com.coinflow.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class CoinflowApplicationTests {

	@Test
	void contextLoads() {
	}

}
