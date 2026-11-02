package com.finallyapp.config;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular build from {@code classpath:/static/} with an SPA fallback: any non-API path
 * without a file extension (e.g. {@code /markets}, {@code /portfolio}) returns {@code index.html}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Defaults to the jar's static/ folder; override for local runs, e.g. file:frontend/dist/frontend/browser/ */
    private final String[] staticLocations;

    public WebConfig(@Value("${spring.web.resources.static-locations:classpath:/static/}") String[] staticLocations) {
        this.staticLocations = staticLocations;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(staticLocations)
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (!resourcePath.isEmpty() && requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (!isSpaRoute(resourcePath)) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }

    static boolean isSpaRoute(String path) {
        if (path.equals("api") || path.startsWith("api/")) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return !last.contains(".");
    }
}
