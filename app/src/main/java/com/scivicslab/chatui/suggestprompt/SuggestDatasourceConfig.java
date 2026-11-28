package com.scivicslab.chatui.suggestprompt;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Gives the named datasource {@code suggest} its JDBC URL from the I/O log DB's path and the HTTP port, unless the URL is
 * set explicitly. A plain property expression cannot take the directory of a path, and the rule is "the same directory as
 * the I/O log DB" ({@link SuggestDatasourceUrl}).
 */
public class SuggestDatasourceConfig implements ConfigSourceInterceptor {

    static final String URL_KEY = "quarkus.datasource.suggest.jdbc.url";
    static final String URL_KEY_QUOTED = "quarkus.datasource.\"suggest\".jdbc.url";

    private static final String IOLOG_DB_PATH = "chat-ui.iolog.db-path";
    private static final String HTTP_PORT = "quarkus.http.port";

    // The defaults IoLogStore declares for the same two properties.
    private static final String DEFAULT_IOLOG_DB_PATH = "chat-ui-iolog";
    private static final int DEFAULT_PORT = 8080;

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue value = context.proceed(name);
        if (value != null || !isUrlKey(name)) return value;
        ConfigValue path = context.proceed(IOLOG_DB_PATH);
        ConfigValue port = context.proceed(HTTP_PORT);
        String dbPath = path == null || path.getValue() == null || path.getValue().isBlank()
                ? DEFAULT_IOLOG_DB_PATH : path.getValue().strip();
        int httpPort = port == null || port.getValue() == null || port.getValue().isBlank()
                ? DEFAULT_PORT : Integer.parseInt(port.getValue().strip());
        return ConfigValue.builder().withName(name).withValue(SuggestDatasourceUrl.of(dbPath, httpPort)).build();
    }

    @Override
    public Iterator<String> iterateNames(ConfigSourceInterceptorContext context) {
        List<String> names = new ArrayList<>();
        context.iterateNames().forEachRemaining(names::add);
        if (!names.contains(URL_KEY_QUOTED) && !names.contains(URL_KEY)) names.add(URL_KEY_QUOTED);
        return names.iterator();
    }

    static boolean isUrlKey(String name) {
        return URL_KEY.equals(name) || URL_KEY_QUOTED.equals(name);
    }
}
