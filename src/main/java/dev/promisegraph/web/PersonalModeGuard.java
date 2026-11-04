package dev.promisegraph.web;

import dev.promisegraph.gmail.MailboxOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * With Gmail connected the app is personal: only the connected account may view it. Any other
 * viewer gets 403 on every API call (the people list is open, since the page needs it to start).
 * Without Gmail, this does nothing and the visibility rules decide per person.
 */
@Configuration
public class PersonalModeGuard implements WebMvcConfigurer {

    private final ObjectProvider<MailboxOwner> mailbox;

    public PersonalModeGuard(ObjectProvider<MailboxOwner> mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
                String viewer = request.getHeader(ApiController.VIEWER);
                Optional<String> owner = Optional.ofNullable(mailbox.getIfAvailable()).flatMap(MailboxOwner::email);
                if (viewer == null || viewer.isBlank() || owner.isEmpty() || owner.get().equalsIgnoreCase(viewer.strip())) {
                    return true; // no viewer: the controllers answer 401; no Gmail: per-person rules apply
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"this app is connected to one Gmail account; only that account can view it\"}");
                return false;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/people");
    }
}
