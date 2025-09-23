package com.calai.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MailBeanSmokeTest {

    @Autowired JavaMailSender mail;

    @Test
    void hasMailBean() {
        assertThat(mail).isNotNull();
    }
}

