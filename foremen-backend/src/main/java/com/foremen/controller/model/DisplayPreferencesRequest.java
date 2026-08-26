package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record DisplayPreferencesRequest(
        @NotNull @Pattern(regexp = "dark|light|system")
        String themeMode,

        @NotNull @Pattern(regexp = "zinc|slate|stone|gray|neutral|blue|green|orange|red")
        String colorScheme,

        @NotNull @Pattern(regexp = "sm|default|lg|xl")
        String fontSize
) {}
