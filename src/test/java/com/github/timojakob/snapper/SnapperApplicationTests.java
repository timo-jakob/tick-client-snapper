package com.github.timojakob.snapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SnapperApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void checkJavaVersion() {
		assertEquals(19, Runtime.version().feature(), "Java 19 needed but not found.");
	}

}
