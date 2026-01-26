package com.calai.backend.entitlement.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BillingProductProperties.class)
public class BillingProductConfig {}
