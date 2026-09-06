package com.foremen.config.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the resource code a concrete controller manages. Placed on the controller class,
 * it lets every inherited CRUD endpoint be guarded without overriding methods. Combined with
 * a method-level {@code PermissionOperation} by the {@code PermissionResolver} to derive the
 * {@code (resource, operation)} pair the {@code PermissionInterceptor} evaluates.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PermissionResource {

    String value();
}
