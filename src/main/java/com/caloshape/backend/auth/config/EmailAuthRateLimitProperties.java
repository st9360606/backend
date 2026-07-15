package com.caloshape.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth.email-rate-limit")
public class EmailAuthRateLimitProperties {

    private String redisPrefix = "caloshape";
    private Duration window = Duration.ofMinutes(15);
    private Limits start = new Limits(3, 20, 5);
    private Limits verify = new Limits(10, 60, 20);

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public void setRedisPrefix(String redisPrefix) {
        this.redisPrefix = redisPrefix;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public Limits getStart() {
        return start;
    }

    public void setStart(Limits start) {
        this.start = start;
    }

    public Limits getVerify() {
        return verify;
    }

    public void setVerify(Limits verify) {
        this.verify = verify;
    }

    public static class Limits {

        private int emailLimit;
        private int ipLimit;
        private int deviceLimit;

        public Limits() {
        }

        public Limits(int emailLimit, int ipLimit, int deviceLimit) {
            this.emailLimit = emailLimit;
            this.ipLimit = ipLimit;
            this.deviceLimit = deviceLimit;
        }

        public int getEmailLimit() {
            return emailLimit;
        }

        public void setEmailLimit(int emailLimit) {
            this.emailLimit = emailLimit;
        }

        public int getIpLimit() {
            return ipLimit;
        }

        public void setIpLimit(int ipLimit) {
            this.ipLimit = ipLimit;
        }

        public int getDeviceLimit() {
            return deviceLimit;
        }

        public void setDeviceLimit(int deviceLimit) {
            this.deviceLimit = deviceLimit;
        }
    }
}
