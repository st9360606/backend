package com.caloshape.backend.entitlement.service;

import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class EntitlementWorkerLeaseRedisIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final List<LettuceConnectionFactory> connectionFactories = new ArrayList<>();

    @AfterEach
    void closeRedisClients() {
        connectionFactories.forEach(LettuceConnectionFactory::destroy);
        connectionFactories.clear();
    }

    @Test
    void onlyOneBackendWorkerExecutesTheSameBatchWhileItsLeaseIsHeld() throws Exception {
        String sharedPrefix = prefix();
        EntitlementWorkerLease firstLease = newLease(sharedPrefix);
        EntitlementWorkerLease secondLease = newLease(sharedPrefix);
        UserEntitlementRepository firstRepository = Mockito.mock(UserEntitlementRepository.class);
        UserEntitlementRepository secondRepository = Mockito.mock(UserEntitlementRepository.class);
        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);

        when(firstRepository.findActiveGooglePlayDueForReverify(any(), any(PageRequest.class)))
                .thenAnswer(ignored -> {
                    firstWorkerStarted.countDown();
                    assertThat(releaseFirstWorker.await(5, TimeUnit.SECONDS)).isTrue();
                    return List.of();
                });

        GooglePlayEntitlementReverifyWorker firstWorker = reverifyWorker(firstRepository, firstLease);
        GooglePlayEntitlementReverifyWorker secondWorker = reverifyWorker(secondRepository, secondLease);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstRun = executor.submit(firstWorker::reverifyActiveGooglePlayEntitlements);
            assertThat(firstWorkerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            secondWorker.reverifyActiveGooglePlayEntitlements();
            verify(secondRepository, never()).findActiveGooglePlayDueForReverify(any(), any(PageRequest.class));

            releaseFirstWorker.countDown();
            firstRun.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredLeaseCanBeRecoveredByAnotherBackendInstance() throws Exception {
        String sharedPrefix = prefix();
        EntitlementWorkerLease first = newLease(sharedPrefix);
        EntitlementWorkerLease second = newLease(sharedPrefix);
        String workerName = "expiry-" + UUID.randomUUID();

        assertThat(first.tryAcquire(workerName, Duration.ofMillis(200))).isNotNull();
        assertThat(second.tryAcquire(workerName, Duration.ofMillis(200))).isNull();

        Thread.sleep(350);

        assertThat(second.tryAcquire(workerName, Duration.ofSeconds(1))).isNotNull();
    }

    @Test
    void nonOwnerCannotReleaseAnotherBackendInstancesLease() {
        String sharedPrefix = prefix();
        EntitlementWorkerLease owner = newLease(sharedPrefix);
        EntitlementWorkerLease other = newLease(sharedPrefix);
        String workerName = "owner-" + UUID.randomUUID();

        EntitlementWorkerLease.Lease held = owner.tryAcquire(workerName, Duration.ofSeconds(5));
        assertThat(held).isNotNull();

        other.release(new EntitlementWorkerLease.Lease(held.key(), "not-the-owner"));
        assertThat(other.tryAcquire(workerName, Duration.ofSeconds(1))).isNull();

        owner.release(held);
        assertThat(other.tryAcquire(workerName, Duration.ofSeconds(1))).isNotNull();
    }

    @Test
    void redisInterruptionFailsClosedAndTheNextRunCanRecover() {
        String sharedPrefix = prefix();
        StringRedisTemplate unavailableRedis = Mockito.mock(StringRedisTemplate.class);
        when(unavailableRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        EntitlementWorkerLease unavailableLease = new EntitlementWorkerLease(unavailableRedis, sharedPrefix);

        assertThat(unavailableLease.tryAcquire("reverify", Duration.ofSeconds(1))).isNull();

        EntitlementWorkerLease recoveredLease = newLease(sharedPrefix);
        assertThat(recoveredLease.tryAcquire("reverify", Duration.ofSeconds(1))).isNotNull();
    }

    private EntitlementWorkerLease newLease() {
        return newLease(prefix());
    }

    private EntitlementWorkerLease newLease(String prefix) {
        StringRedisTemplate template = connectedRedisTemplate();
        return new EntitlementWorkerLease(template, prefix);
    }

    private static String prefix() {
        return "caloshape-lease-it-" + UUID.randomUUID();
    }

    private StringRedisTemplate connectedRedisTemplate() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, clientConfiguration);
        factory.afterPropertiesSet();
        factory.start();
        connectionFactories.add(factory);

        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static GooglePlayEntitlementReverifyWorker reverifyWorker(
            UserEntitlementRepository repository,
            EntitlementWorkerLease lease
    ) {
        EntitlementSyncService syncService = Mockito.mock(EntitlementSyncService.class);
        PurchaseTokenCrypto tokenCrypto = Mockito.mock(PurchaseTokenCrypto.class);
        when(tokenCrypto.enabled()).thenReturn(true);

        GooglePlayEntitlementReverifyWorker worker = new GooglePlayEntitlementReverifyWorker(
                repository,
                syncService,
                tokenCrypto,
                lease
        );
        ReflectionTestUtils.setField(worker, "leaseTtl", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(worker, "staleAfter", Duration.ofHours(1));
        ReflectionTestUtils.setField(worker, "batchSize", 10);
        return worker;
    }
}
