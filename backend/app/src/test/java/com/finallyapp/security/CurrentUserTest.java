package com.finallyapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserTest {

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
        CurrentUser.setAuthEnabled(true);
    }

    @Test
    void noUserFailsLoudlyWhenAuthIsEnabled() {
        assertThat(CurrentUser.optionalId()).isEmpty();
        assertThatThrownBy(CurrentUser::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anonymousIsNotAUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(CurrentUser.optionalId()).isEmpty();
    }

    @Test
    void authenticatedUserIsTheAuthenticationName() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("kc-sub-1", null, List.of()));
        assertThat(CurrentUser.id()).isEqualTo("kc-sub-1");
    }

    @Test
    void runAsOverridesAndRestores() {
        CurrentUser.runAs("u1", () -> {
            assertThat(CurrentUser.id()).isEqualTo("u1");
            assertThat(CurrentUser.callAs("u2", CurrentUser::id)).isEqualTo("u2");
            assertThat(CurrentUser.id()).isEqualTo("u1");
        });
        assertThat(CurrentUser.optionalId()).isEmpty();
    }

    @Test
    void authDisabledFallsBackToLegacyUser() {
        CurrentUser.setAuthEnabled(false);
        assertThat(CurrentUser.id()).isEqualTo(CurrentUser.LEGACY_USER);
    }
}
