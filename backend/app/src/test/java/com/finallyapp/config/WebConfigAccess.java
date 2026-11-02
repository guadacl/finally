package com.finallyapp.config;

/** Test accessor for package-private {@link WebConfig} helpers. */
public final class WebConfigAccess {
    private WebConfigAccess() {
    }

    public static boolean isSpaRoute(String path) {
        return WebConfig.isSpaRoute(path);
    }
}
