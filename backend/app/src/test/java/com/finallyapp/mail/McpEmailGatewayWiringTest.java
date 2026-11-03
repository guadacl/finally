package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The integration tests replace the gateway with a mock, so they never let Spring construct it. With two
 * constructors Spring needs exactly one marked {@link Autowired}, otherwise the app fails to start
 * ("No default constructor found").
 */
class McpEmailGatewayWiringTest {

    @Test
    void springCanChooseAConstructor() {
        Constructor<?>[] ctors = McpEmailGateway.class.getDeclaredConstructors();
        long autowired = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
        assertThat(ctors.length == 1 || autowired == 1).isTrue();
    }
}
