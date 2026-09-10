package com.herald.config;

import java.lang.annotation.*;
import org.springframework.context.annotation.Conditional;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ProviderConfiguredCondition.class)
public @interface ConditionalOnProvider { String value(); }
