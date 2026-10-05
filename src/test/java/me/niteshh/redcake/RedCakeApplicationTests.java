package me.niteshh.redcake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole Spring context. Uses a dedicated high port so the test
 * still passes while a real Redis/RedCake occupies 6379 on the developer machine.
 */
@SpringBootTest(args = {"--port", "36379"})
class RedCakeApplicationTests {

    @Test
    void contextLoads() {
    }

}
