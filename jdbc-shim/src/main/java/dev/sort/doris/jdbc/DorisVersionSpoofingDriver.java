/*
 * Copyright 2026 Sortdev SRL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package dev.sort.doris.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A thin JDBC {@link Driver} that delegates everything to the MySQL Connector/J driver but makes
 * Apache Doris report a MySQL-8.0 server version, so DataGrip's version-gated MySQL SQL parser
 * enables 8.0 constructs (window functions {@code OVER (...)}, CTEs, {@code QUALIFY}).
 *
 * <p>Doris speaks the MySQL protocol and reports version "5.7.99". DataGrip derives the version the
 * parser gates on ("Effective version") NOT from JDBC metadata but from a SQL probe run in its
 * out-of-process JDBC layer:
 * <pre>select version(), @@version_comment, database()</pre>
 * We therefore intercept two channels here:
 * <ul>
 *   <li>{@code DatabaseMetaData.getDatabaseProductVersion()} (drives the cosmetic "DBMS" line), and</li>
 *   <li>the {@code version()} column of the probe query above (drives the "Effective version" the
 *       parser actually uses) — rewritten to 8.0.33 while {@code @@version_comment} is left intact
 *       so the data source still reads "Apache Doris".</li>
 * </ul>
 *
 * <p>No compile-time dependency on MySQL Connector/J: the delegate is loaded reflectively, so this
 * jar builds against a plain JDK and just needs Connector/J on the same classpath at runtime.
 */
public final class DorisVersionSpoofingDriver implements Driver {

    /** Version handed to DataGrip in place of Doris's "5.7.99". >= 8.0 enables window fns / CTEs. */
    private static final String SPOOFED_VERSION = "8.0.33";
    private static final int SPOOFED_MAJOR = 8;
    private static final int SPOOFED_MINOR = 0;

    private static final String DELEGATE_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private final Driver delegate;

    public DorisVersionSpoofingDriver() {
        try {
            this.delegate = (Driver) Class.forName(DELEGATE_DRIVER_CLASS)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Doris version-spoofing driver: could not load delegate " + DELEGATE_DRIVER_CLASS
                            + " — ensure MySQL Connector/J is on the driver classpath.", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        Connection real = delegate.connect(url, info);
        return real == null ? null : spoofConnection(real);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    // --- the DataGrip version probe we rewrite: "select version(), @@version_comment, database()" ---

    private static boolean isVersionProbe(String sql) {
        if (sql == null) {
            return false;
        }
        String s = sql.toLowerCase();
        return s.contains("version()") && s.contains("version_comment");
    }

    /** True for the version() column of the probe (index 1, or label "version()"). */
    private static boolean isVersionColumn(Object[] args) {
        if (args == null || args.length < 1) {
            return false;
        }
        Object c = args[0];
        return (c instanceof Integer && (Integer) c == 1)
                || (c instanceof String && ((String) c).equalsIgnoreCase("version()"));
    }

    // --- proxies: delegate every method, intercept only the version reads ---

    private static Connection spoofConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                DorisVersionSpoofingDriver.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getMetaData".equals(name) && method.getParameterCount() == 0) {
                        return spoofMetaData((DatabaseMetaData) invoke(real, method, args));
                    }
                    if ("createStatement".equals(name)) {
                        return spoofStatement(invoke(real, method, args), null);
                    }
                    if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                        String sql = (args != null && args.length > 0 && args[0] instanceof String)
                                ? (String) args[0] : null;
                        return spoofStatement(invoke(real, method, args), sql);
                    }
                    return invoke(real, method, args);
                });
    }

    private static DatabaseMetaData spoofMetaData(DatabaseMetaData real) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                DorisVersionSpoofingDriver.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDatabaseProductVersion":
                            return SPOOFED_VERSION;
                        case "getDatabaseMajorVersion":
                            return SPOOFED_MAJOR;
                        case "getDatabaseMinorVersion":
                            return SPOOFED_MINOR;
                        default:
                            return invoke(real, method, args);
                    }
                });
    }

    /**
     * Wrap a Statement/PreparedStatement/CallableStatement so that if it runs the version probe,
     * the returned ResultSet reports SPOOFED_VERSION for the version() column. {@code boundSql} is
     * non-null for prepared statements (whose executeQuery() takes no SQL argument).
     */
    private static Object spoofStatement(Object realStmt, String boundSql) {
        Class<?> iface = realStmt instanceof CallableStatement ? CallableStatement.class
                : realStmt instanceof PreparedStatement ? PreparedStatement.class
                : Statement.class;
        return Proxy.newProxyInstance(
                DorisVersionSpoofingDriver.class.getClassLoader(),
                new Class<?>[]{iface},
                new InvocationHandler() {
                    private boolean lastExecWasProbe;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        String sql = (args != null && args.length > 0 && args[0] instanceof String)
                                ? (String) args[0] : boundSql;
                        if ("executeQuery".equals(name)) {
                            ResultSet rs = (ResultSet) DorisVersionSpoofingDriver.invoke(realStmt, method, args);
                            if (isVersionProbe(sql) && rs != null) {
                                System.err.println("[doris-shim] intercepting version probe, spoofing version() -> "
                                        + SPOOFED_VERSION + " for: " + sql);
                                return spoofResultSet(rs);
                            }
                            return rs;
                        }
                        if (name.startsWith("execute")) {
                            lastExecWasProbe = isVersionProbe(sql);
                            return DorisVersionSpoofingDriver.invoke(realStmt, method, args);
                        }
                        if ("getResultSet".equals(name)) {
                            Object rs = DorisVersionSpoofingDriver.invoke(realStmt, method, args);
                            return lastExecWasProbe && rs != null ? spoofResultSet((ResultSet) rs) : rs;
                        }
                        return DorisVersionSpoofingDriver.invoke(realStmt, method, args);
                    }
                });
    }

    private static ResultSet spoofResultSet(ResultSet real) {
        return (ResultSet) Proxy.newProxyInstance(
                DorisVersionSpoofingDriver.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    // Always run the real getter first (keeps driver cursor/wasNull state consistent),
                    // then override only the version() column value.
                    Object realValue = invoke(real, method, args);
                    String name = method.getName();
                    if (("getString".equals(name) || "getObject".equals(name)) && isVersionColumn(args)) {
                        return SPOOFED_VERSION;
                    }
                    return realValue;
                });
    }

    /** Invoke on the delegate, unwrapping reflection exceptions so the real SQLException propagates. */
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
