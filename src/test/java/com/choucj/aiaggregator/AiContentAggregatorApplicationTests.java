package com.choucj.aiaggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"wechat.mp.enabled=false",
		"schedule.run-on-startup=false"
})
class AiContentAggregatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
