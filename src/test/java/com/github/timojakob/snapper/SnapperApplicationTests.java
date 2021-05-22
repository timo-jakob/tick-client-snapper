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
		String javaVersionStr = System.getProperty("java.version");
		int dotIndex = javaVersionStr.indexOf(".");
		String majorVersionStr = dotIndex < 0 ? "UNKNOWN" : javaVersionStr.substring(0, dotIndex);

		assertEquals("16", majorVersionStr, "Testing with wrong java version");
	}

}
