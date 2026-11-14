package kr.seoulhousing.monitor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final UserAccountRepository users;
    GoogleLoginSuccessHandler(UserAccountRepository users){this.users=users;}
    @Override public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication) throws IOException {
        if(authentication.getPrincipal() instanceof OAuth2User user) users.upsertGoogle(String.valueOf(user.getAttribute("sub")),String.valueOf(user.getAttribute("email")),String.valueOf(user.getAttribute("name")));
        response.sendRedirect("/");
    }
}
