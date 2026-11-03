package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The integration tests replace the gateways with mocks, so they never let Spring construct them. With several
 * constructors Spring needs exactly one marked {@link Autowired}, otherwise the app fails to start
 * ("No default constructor found").
 */
class McpEmailGatewayWiringTest {

    @Test
    void springCanChooseAConstructor() {
        for (Class<?> bean : List.of(McpToolClient.class, McpEmailGateway.class, McpSmsGateway.class,
                NotificationMailer.class, NotificationSmsSender.class, UserContacts.class,
                UserContactSyncFilter.class)) {
            Constructor<?>[] ctors = bean.getDeclaredConstructors();
            long autowired = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            assertThat(ctors.length == 1 || autowired == 1).as(bean.getSimpleName()).isTrue();
        }
    }
}
