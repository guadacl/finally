package com.finallyapp.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Beans with several constructors need exactly one {@link Autowired} one, or Spring cannot create them
 * ("No default constructor found") - integration tests that mock these beans would not notice.
 */
class WorkflowBeanConstructorsTest {

    @Test
    void springCanChooseAConstructor() {
        for (Class<?> bean : List.of(DirectKanbanWorkflow.class, CamundaKanbanWorkflow.class,
                EmailNotifyJobWorker.class, SmsNotifyJobWorker.class, NotifyJobWorker.class)) {
            Constructor<?>[] ctors = bean.getDeclaredConstructors();
            long autowired = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            assertThat(ctors.length == 1 || autowired == 1).as(bean.getSimpleName()).isTrue();
        }
    }
}
