package com.calai.backend.testsupport;

import com.calai.backend.foodlog.task.ProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
class ProviderClientListTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void list_all_provider_clients_and_codes() {
        Map<String, ProviderClient> beans = ctx.getBeansOfType(ProviderClient.class);

        System.out.println("=== ProviderClient beans ===");
        beans.forEach((name, bean) -> {
            String code;
            try {
                code = bean.providerCode();
            } catch (Exception e) {
                code = "ERROR: " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            System.out.println(name + " -> " + code + " (" + bean.getClass().getName() + ")");
        });
        System.out.println("=== total: " + beans.size() + " ===");
    }
}
