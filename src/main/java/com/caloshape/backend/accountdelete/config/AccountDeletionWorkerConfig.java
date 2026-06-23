package com.caloshape.backend.accountdelete.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountDeletionWorkerProperties.class)
public class AccountDeletionWorkerConfig {}
