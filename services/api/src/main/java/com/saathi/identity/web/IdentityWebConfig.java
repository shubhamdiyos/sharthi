package com.saathi.identity.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the two request-path pieces that make a controller able to ask "who is this?".
 *
 * <p>This class is in {@code identity} rather than the shared kernel's web package because a
 * {@code WebMvcConfigurer} that resolves a principal has to name the principal's type, and
 * {@code ModuleBoundaryTest#theSharedKernelDependsOnNoModule} exists to stop the kernel from
 * importing a module. Putting the wiring in the leaf would make the dependency graph a cycle for the
 * sake of one tidy-looking package.
 *
 * <p>Neither is a Spring Security filter chain: §11's authorization decisions are per-endpoint
 * checks against the resolved principal, and §12.4's session semantics are read from the row, so
 * adding the security starter's defaults would replace the reviewed behaviour with a different one.
 */
@Configuration
@RequiredArgsConstructor
public class IdentityWebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;
    private final CsrfInterceptor csrf;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csrf);
    }
}
