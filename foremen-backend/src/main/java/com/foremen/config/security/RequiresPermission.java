package com.foremen.config.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the {@code resource} and {@code operation} required to invoke the annotated
 * controller method. Enforced at runtime by the {@code PermissionInterceptor}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    String resource();

    String operation();
}
