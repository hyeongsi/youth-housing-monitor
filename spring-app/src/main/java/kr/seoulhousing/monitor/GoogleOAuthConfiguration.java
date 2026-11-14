package kr.seoulhousing.monitor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
class GoogleOAuthConfiguration {
    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http,ObjectProvider<ClientRegistrationRepository> registrations,GoogleLoginSuccessHandler successHandler) throws Exception {
        http.authorizeHttpRequests(requests->requests.requestMatchers("/api/auth/**","/api/me").permitAll().requestMatchers("/api/me/**").authenticated().anyRequest().permitAll())
                .csrf(csrf->csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .logout(logout->logout.disable());
        if(registrations.getIfAvailable()!=null) http.oauth2Login(login->login.successHandler(successHandler));
        return http.build();
    }

    @Configuration
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${housing.auth.google.client-id:}') && T(org.springframework.util.StringUtils).hasText('${housing.auth.google.client-secret:}')")
    static class GoogleClientConfiguration {
        @Bean
        ClientRegistrationRepository googleRegistration(@Value("${housing.auth.google.client-id}") String clientId,@Value("${housing.auth.google.client-secret}") String clientSecret) {
            ClientRegistration google=ClientRegistration.withRegistrationId("google")
                    .clientId(clientId).clientSecret(clientSecret).scope("openid","profile","email")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                    .userNameAttributeName("sub").clientName("Google").build();
            return new InMemoryClientRegistrationRepository(google);
        }
    }
}
