package com.caloshape.backend.users.user;

import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.users.user.controller.UserMeController;
import com.caloshape.backend.users.user.dto.MeDto;
import com.caloshape.backend.users.user.dto.UpdateNameRequest;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMeControllerTest {

    private static final long USER_ID = 1L;

    @Mock
    private UserRepo users;

    @Mock
    private AuthContext auth;

    @InjectMocks
    private UserMeController controller;

    @BeforeEach
    void setUp() {
        when(auth.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void updateMe_shouldTrimAndCollapseWhitespace() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        MeDto dto = controller.updateMe(new UpdateNameRequest("  Kurt\n   Wu  "));

        assertThat(dto.name()).isEqualTo("Kurt Wu");
        assertThat(user.getName()).isEqualTo("Kurt Wu");
        verify(users).save(user);
    }

    @Test
    void updateMe_shouldRejectNullBody() {
        assertBadRequest(() -> controller.updateMe(null));
        verify(users, never()).findById(USER_ID);
    }

    @Test
    void updateMe_shouldRejectBlankName() {
        assertBadRequest(() -> controller.updateMe(new UpdateNameRequest("   ")));
        verify(users, never()).findById(USER_ID);
    }

    @Test
    void updateMe_shouldRejectTooLongName() {
        String tooLong = "A".repeat(41);

        assertBadRequest(() -> controller.updateMe(new UpdateNameRequest(tooLong)));
        verify(users, never()).findById(USER_ID);
    }

    private static void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("kurt@example.com");
        return user;
    }
}
