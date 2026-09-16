package io.reladynamo.core.bridge;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.finder.RelatedFinder;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the {@code *Data} instance belonging to a finder.
 *
 * <p>Reladomo's generated database object can inflate data from a {@code ResultSet}, which is no use
 * to an adapter reading DynamoDB items. There is no generic public factory, so this resolves the
 * generated data class by name — {@code <businessClass>Data}, the convention the code generator
 * always follows — and constructs it.
 *
 * <p>Resolution is cached per finder: reflection on every row of every query would be a real cost in
 * the hot read path.
 *
 * <p>Java 11 baseline.
 */
public final class MithraDataFactory {

    private static final Map<String, Constructor<?>> CACHE = new ConcurrentHashMap<>();

    private MithraDataFactory() {
    }

    public static MithraDataObject newData(RelatedFinder finder) {
        if (finder == null) {
            throw new IllegalArgumentException("finder is required to resolve the data class");
        }
        MithraObjectPortal portal = finder.getMithraObjectPortal();
        if (portal == null) {
            throw new IllegalStateException(
                    "the finder has no portal yet; Reladomo must be configured before objects can be "
                            + "materialised");
        }
        // getBusinessClassName() returns the SIMPLE name, which cannot be resolved by reflection.
        // getFinderClassName() carries the package, so the data class is derived from it.
        String finderClass = finder.getFinderClassName();
        Constructor<?> ctor = CACHE.computeIfAbsent(finderClass, MithraDataFactory::resolve);
        try {
            return (MithraDataObject) ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not instantiate data class for " + finderClass, e);
        }
    }

    private static Constructor<?> resolve(String finderClass) {
        if (!finderClass.endsWith("Finder")) {
            throw new IllegalStateException(
                    "expected a generated finder class name ending in 'Finder', got: " + finderClass);
        }
        String dataClass = finderClass.substring(0, finderClass.length() - "Finder".length()) + "Data";
        try {
            Constructor<?> c = Class.forName(dataClass).getDeclaredConstructor();
            c.setAccessible(true);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "no generated data class '" + dataClass + "' for finder " + finderClass
                            + ". Reladomo's generator emits <ClassName>Data alongside the object; if "
                            + "that convention has changed this resolution needs updating.", e);
        }
    }
}
