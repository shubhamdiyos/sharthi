package com.saathi.identity.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.identity.session.SessionAuthFilter;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gives a controller its caller, or answers 401.
 *
 * <p>The alternative is a static holder that any class can read, which is how "who is asking" ends up
 * being consulted in a place nobody reviewed. Here the dependency is in the method signature: a
 * handler that takes {@code AuthenticatedPrincipal} is an authenticated endpoint as far as a reader
 * (and §10.5's endpoint index) is concerned, and one that does not is public.
 *
 * <p>Only a parameter of exactly that type is resolved, and MVC asks resolvers about handler
 * parameters and nothing else, so claiming the type claims the endpoint.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthenticatedPrincipal.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(SessionAuthFilter.REQUEST_ATTRIBUTE,
            RequestAttributes.SCOPE_REQUEST);
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "A session is required for this request.");
        }
        return principal;
    }
}
