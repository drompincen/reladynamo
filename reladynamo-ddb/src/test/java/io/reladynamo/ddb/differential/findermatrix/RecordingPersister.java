package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister;
import io.reladynamo.ddb.persist.DynamoDbPersister;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording decorator around the bound persister interfaces. Forwards every call; records
 * find/count entry so a MATCH cannot pass on a cache hit. Implements dated and transactional
 * interfaces used by binding — a reader-only proxy would break writes.
 */
final class RecordingPersister implements InvocationHandler {

    private final DynamoDbPersister real;
    private final AtomicInteger findCalls = new AtomicInteger();
    private final AtomicInteger countCalls = new AtomicInteger();
    private final Set<String> methods = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final MithraObjectReader proxy;

    RecordingPersister(DynamoDbPersister real) {
        this.real = real;
        this.proxy = (MithraObjectReader) Proxy.newProxyInstance(
                DynamoDbPersister.class.getClassLoader(),
                new Class[] {MithraObjectReader.class, MithraDatedObjectPersister.class},
                this);
    }

    MithraObjectReader proxy() {
        return proxy;
    }

    void reset() {
        findCalls.set(0);
        countCalls.set(0);
        methods.clear();
    }

    Set<String> methodsReached() {
        return new LinkedHashSet<String>(methods);
    }

    int findCalls() {
        return findCalls.get();
    }

    int countCalls() {
        return countCalls.get();
    }

    int readerEntries() {
        return findCalls.get() + countCalls.get();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (!isObjectMethod(name)) {
            methods.add(name);
        }
        if ("find".equals(name) && args != null && args.length > 0 && args[0] instanceof AnalyzedOperation) {
            findCalls.incrementAndGet();
        } else if ("count".equals(name) && args != null && args.length > 0 && args[0] instanceof Operation) {
            countCalls.incrementAndGet();
        }
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                throw cause;
            }
            throw e;
        }
    }

    private static boolean isObjectMethod(String name) {
        return "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name);
    }
}
