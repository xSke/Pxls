package space.pxls.util;

import com.typesafe.config.ConfigException;

import java.util.*;
import java.util.function.Supplier;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.*;

public class Util {
    public static String generateRandomToken() {
        String charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random rand = new Random();
        StringBuilder res = new StringBuilder();
        for (int i = 0; i <= 32; i++) {
            int randIndex = rand.nextInt(charset.length());
            res.append(charset.charAt(randIndex));
        }
        return res.toString();
    }

    /**
     * Returns a default value if the supplier throws ConfigException.Missing,
     * otherwise the value in the getter.
     * @param method Config getter supplier
     * @param val Default value
     * @param <T> Type of value
     * @return value or default value
     */
    public static <T> T defaultConfigVal(Supplier<T> method, T val) {
        try {
            return method.get();
        } catch (ConfigException.Missing ex) {
            return val;
        }
    }

    private static ResourceBundle localization = ResourceBundle.getBundle("Localization");

    public static String i18n(String s) {
        return localization.getString(s);
    }

    public static Locale negotiateLocale(HttpServerExchange exchange) {
        List<Locale> locales = LocaleUtils.getLocalesFromHeader(exchange.getRequestHeaders().get(Headers.ACCEPT_LANGUAGE));
        locales.retainAll(SUPPORTED_LOCALES);
        locales.add(FALLBACK_LOCALE);

        return locales.get(0);
    }

    public static List<Locale> SUPPORTED_LOCALES = List.of(Locale.forLanguageTag("en-US"));
    public static Locale FALLBACK_LOCALE = SUPPORTED_LOCALES.get(0);
}
