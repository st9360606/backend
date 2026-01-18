package com.calai.backend.testsupport;

import org.springframework.test.context.ActiveProfiles;

/**
 * ✅ 給所有 Spring 測試共用的基底類別
 * - 強制使用 test profile
 * - 避免 CI / IDE / 環境變數把 profile 弄歪
 */
@ActiveProfiles("test")
public abstract class BaseSpringTest {
}
