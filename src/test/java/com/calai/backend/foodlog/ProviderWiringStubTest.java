package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.LogMealProviderClient;
import com.calai.backend.foodlog.provider.ProviderConfig;
import com.calai.backend.foodlog.service.LogMealTokenService;
import com.calai.backend.foodlog.task.StubProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderWiringStubTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(LogMealTokenService.class, () -> Mockito.mock(LogMealTokenService.class));

    @Test
    void should_wire_stub_provider() {
        contextRunner
                .withPropertyValues("app.foodlog.provider=STUB")
                .run(context -> {
                    assertThat(context).hasSingleBean(StubProviderClient.class);
                    assertThat(context).doesNotHaveBean(LogMealProviderClient.class);
                });
    }

    @Test
    void should_wire_logmeal_provider() {
        contextRunner
                .withPropertyValues(
                        "app.foodlog.provider=LOGMEAL",
                        "app.provider.logmeal.base-url=https://api.logmeal.com",
                        "app.crypto.aesgcm.key-b64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LogMealProviderClient.class);
                    assertThat(context).doesNotHaveBean(StubProviderClient.class);
                });
    }
}
