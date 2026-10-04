package com.kksg.applicationServices.repository.controller;

import com.kksg.applicationServices.identity.entity.User;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies a fixed {@link User} for {@code @AuthenticationPrincipal} parameters in standalone MockMvc.
 *
 * <p>Needed because standalone setup has no Spring Security, and without a resolver Spring MVC would
 * treat the {@code User} parameter as a model attribute and hand the controller a blank, data-bound
 * instance. That would still produce a 200, so the tests would pass while asserting nothing about the
 * principal - the failure mode worth avoiding here is a green test, not a red one.
 */
class TestPrincipalResolver implements HandlerMethodArgumentResolver {

    static final int USER_ID = 42;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return User.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("principal@example.invalid");
        return user;
    }
}
