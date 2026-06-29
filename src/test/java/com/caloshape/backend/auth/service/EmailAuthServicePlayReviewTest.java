package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.config.PlayReviewAccessProperties;
import com.caloshape.backend.auth.email.EmailLoginCode;
import com.caloshape.backend.auth.repo.EmailLoginCodeRepository;
import com.caloshape.backend.entitlement.service.PlayReviewEntitlementService;
import com.caloshape.backend.fasting.service.FastingPlanService;
import com.caloshape.backend.fasting.support.ClientTimeZoneResolver;
import com.caloshape.backend.users.user.repo.UserRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailAuthServicePlayReviewTest {

    @Test
    void startStoresFixedReviewCodeWithoutSendingEmail() throws Exception {
        EmailLoginCodeRepository codes = mock(EmailLoginCodeRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(codes.save(any(EmailLoginCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailAuthService service = service(codes, events);

        service.start(new com.caloshape.backend.auth.dto.StartRequest(" PLAY-REVIEW@CALOSHAPE.COM "), "ip", "ua");

        ArgumentCaptor<EmailLoginCode> captor = ArgumentCaptor.forClass(EmailLoginCode.class);
        verify(codes).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("play-review@caloshape.com");
        assertThat(captor.getValue().getCodeHash()).isEqualTo(sha256("4827"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static EmailAuthService service(
            EmailLoginCodeRepository codes,
            ApplicationEventPublisher events
    ) {
        PlayReviewAccessProperties properties = new PlayReviewAccessProperties();
        properties.setEnabled(true);
        properties.setEmail("play-review@caloshape.com");
        properties.setCode("4827");
        properties.setEntitlementValidity(Duration.ofDays(3650));

        EmailAuthService service = new EmailAuthService(
                codes,
                mock(UserRepo.class),
                mock(TokenService.class),
                mock(FastingPlanService.class),
                mock(ClientTimeZoneResolver.class),
                events,
                new PlayReviewAccessService(properties),
                mock(PlayReviewEntitlementService.class)
        );
        service.enabled = true;
        service.otpLen = 4;
        service.ttlMin = 10;
        return service;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
