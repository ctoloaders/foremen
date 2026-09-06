package com.foremen.config.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the {@code operation} a CRUD method performs. Placed on the CRUD interface
 * {@code default} methods so every implementing controller inherits the classification.
 * Combined with the class-level {@code @PermissionResource} by the {@code PermissionResolver}
 * to derive the {@code (resource, operation)} pair guarded by the {@code PermissionInterceptor}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PermissionOperation {

    String value();
}
