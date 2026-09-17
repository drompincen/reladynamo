package io.reladynamo.ddb.differential.findermatrix;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts SELECTs against finder-matrix fixture tables and can forbid them during DDB
 * measurement. Connection-manager initialisation is outside measurement.
 */
public final class SqlReadProbe {

    private static final String[] FIXTURE_TABLES = {
            "DIFF_FINDER_VALUE", "DIFF_BALANCE", "DIFF_AUDIT", "DIFF_ENTRY"
    };

    private final AtomicInteger selects = new AtomicInteger();
    private volatile boolean forbid;

    void reset() {
        selects.set(0);
    }

    void forbid() {
        forbid = true;
    }

    void allow() {
        forbid = false;
    }

    int selects() {
        return selects.get();
    }

    public Connection wrap(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[] {Connection.class},
                new Handler(real, null));
    }

    private void onSql(String sql) {
        if (sql == null) {
            return;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("SELECT")) {
            return;
        }
        boolean fixture = false;
        for (int i = 0; i < FIXTURE_TABLES.length; i++) {
            if (upper.contains(FIXTURE_TABLES[i])) {
                fixture = true;
                break;
            }
        }
        if (!fixture) {
            return;
        }
        selects.incrementAndGet();
        if (forbid) {
            throw new IllegalStateException(
                    "SQL SELECT against a finder-matrix fixture table during DDB measurement: " + sql);
        }
    }

    private final class Handler implements InvocationHandler {
        private final Object real;
        private final String sql;

        Handler(Object real, String sql) {
            this.real = real;
            this.sql = sql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] instanceof Class) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) {
                    return proxy;
                }
            }
            if (isExecute(name)) {
                onSql(sql);
            }
            Object result;
            try {
                result = method.invoke(real, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    throw cause;
                }
                throw e;
            }
            String nextSql = sql;
            if (args != null && args.length > 0 && args[0] instanceof String
                    && (name.startsWith("prepare") || name.startsWith("create"))) {
                nextSql = (String) args[0];
            }
            if (result instanceof PreparedStatement) {
                return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new Handler(result, nextSql));
            }
            if (result instanceof Statement && !(result instanceof PreparedStatement)) {
                return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class[] {Statement.class},
                        new Handler(result, nextSql));
            }
            return result;
        }

        private boolean isExecute(String name) {
            return "executeQuery".equals(name)
                    || "execute".equals(name)
                    || "executeUpdate".equals(name)
                    || "executeLargeUpdate".equals(name);
        }
    }
}
