package dev.rovernotes;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the request limiter out of the servlet container's own filter chain.
 *
 * <p>The limiter has to run inside a security chain, after authorization, so that it can
 * count against the authenticated caller and so that an unauthorized request is refused as
 * unauthorized rather than as too frequent. Boot registers any {@code Filter} bean with
 * the container as well, where it would run in front of Spring Security and see neither;
 * the disabled registration below is what prevents that second, useless copy.
 */
@Configuration
class RateLimitConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterNotInTheContainerChain(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
