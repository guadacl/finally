package com.finallyapp.security;

import com.finallyapp.api.UserApi;
import com.finallyapp.api.model.UserInfo;
import com.finallyapp.config.FinallyProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/me}: who is logged in (shown in the header next to the logout button). */
@RestController
public class MeController implements UserApi {

    private final boolean authEnabled;

    public MeController(FinallyProperties props) {
        this.authEnabled = props.auth().enabled();
    }

    @Override
    public ResponseEntity<UserInfo> getCurrentUser() {
        String id = CurrentUser.id();
        UserInfo info = new UserInfo(id, id).authEnabled(authEnabled);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser user) {
            String username = user.getPreferredUsername();
            info.username(username != null ? username : id)
                    .email(user.getEmail())
                    .name(user.getFullName());
        } else if (!authEnabled) {
            info.username("local user");
        }
        return ResponseEntity.ok(info);
    }
}
