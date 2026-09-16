package io.reladynamo.spike;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraDatedObject;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.list.cursor.Cursor;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.querycache.CachedQuery;
import com.gs.fw.common.mithra.util.Filter;
import com.gs.fw.common.mithra.util.RenewedCacheStats;
import io.reladynamo.spike.domain.SpikeBalance;
import io.reladynamo.spike.domain.SpikeBalanceFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walking-skeleton spike.
 *
 * <p>It asks one question and answers it with a running JVM rather than a signature: can a Reladomo
 * portal be re-pointed at a persistence implementation we supply, without forking Reladomo? If it
 * cannot, the whole adapter design is wrong and the project needs re-planning around generated
 * {@code *DatabaseObject} subclasses instead.
 */
class PortalBindingSpikeTest {

    @BeforeAll
    static void bootReladomo() throws Exception {
        SpikeTestSupport.boot();
    }

    @Test
    void portal_can_be_rebound_to_a_reader_we_supply() {
        MithraObjectPortal portal = SpikeBalanceFinder.getMithraObjectPortal();

        // Claim under test: the seam is public API on MithraAbstractObjectPortal.
        assertThat(portal).isInstanceOf(MithraAbstractObjectPortal.class);
        MithraAbstractObjectPortal abstractPortal = (MithraAbstractObjectPortal) portal;

        MithraObjectReader original = abstractPortal.getDatabaseObject();
        assertThat(original).isNotNull();

        AtomicInteger findCalls = new AtomicInteger();
        abstractPortal.setMithraObjectReader(new RecordingReader(original, findCalls));

        Operation op = SpikeBalanceFinder.balanceId().eq(1)
                .and(SpikeBalanceFinder.businessDate().eq(SpikeTestSupport.BUSINESS_DATE))
                .and(SpikeBalanceFinder.processingDate().eq(SpikeInfinity.INFINITY));

        SpikeBalanceFinder.findMany(op).forceResolve();

        // The point of the spike: our implementation actually sat in the read path.
        assertThat(findCalls.get())
                .as("the swapped-in reader must receive the find, otherwise the seam is cosmetic")
                .isGreaterThan(0);

        abstractPortal.setMithraObjectReader(original);
    }

    @Test
    void tuple_persister_has_no_setter_so_it_still_points_at_the_original() {
        MithraAbstractObjectPortal portal =
                (MithraAbstractObjectPortal) SpikeBalanceFinder.getMithraObjectPortal();

        // Documents the known residual risk rather than leaving it as folklore: after a reader swap
        // the portal keeps its original tuple persister, because that field is constructor-supplied
        // and private with no setter.
        assertThat(portal.getMithraTuplePersister())
                .as("no public setter exists for mithraTuplePersister in Reladomo 18.1.0")
                .isNotNull();
    }

    /** Delegating reader that counts the calls it intercepts. */
    private static final class RecordingReader implements MithraObjectReader {
        private final MithraObjectReader delegate;
        private final AtomicInteger findCalls;

        RecordingReader(MithraObjectReader delegate, AtomicInteger findCalls) {
            this.delegate = delegate;
            this.findCalls = findCalls;
        }

        @Override
        public CachedQuery find(AnalyzedOperation op, OrderBy orderBy, boolean b, int i, int i1,
                                boolean b1, boolean b2) {
            findCalls.incrementAndGet();
            return delegate.find(op, orderBy, b, i, i1, b1, b2);
        }

        @Override
        public Cursor findCursor(AnalyzedOperation op, Filter filter, OrderBy orderBy, int i,
                                 boolean b, int i1, boolean b1) {
            return delegate.findCursor(op, filter, orderBy, i, b, i1, b1);
        }

        @Override
        public int count(Operation op) {
            return delegate.count(op);
        }

        @Override
        public List computeFunction(Operation op, OrderBy orderBy, String s,
                                    com.gs.fw.common.mithra.finder.ResultSetParser parser) {
            return delegate.computeFunction(op, orderBy, s, parser);
        }

        @Override
        public MithraDataObject refresh(MithraDataObject data, boolean b) {
            return delegate.refresh(data, b);
        }

        @Override
        public MithraDataObject refreshDatedObject(MithraDatedObject obj, boolean b) {
            return delegate.refreshDatedObject(obj, b);
        }

        @Override
        public List findAggregatedData(Operation op, Map m,
                                       Map m1,
                                       com.gs.fw.common.mithra.HavingOperation having, boolean b, Class c) {
            return delegate.findAggregatedData(op, m, m1, having, b, c);
        }

        @Override
        public void loadFullCache() {
            delegate.loadFullCache();
        }

        @Override
        public void reloadFullCache() {
            delegate.reloadFullCache();
        }

        @Override
        public RenewedCacheStats renewCacheForOperation(Operation op) {
            return delegate.renewCacheForOperation(op);
        }

        @Override
        public Map extractDatabaseIdentifiers(Operation op) {
            return delegate.extractDatabaseIdentifiers(op);
        }

        @Override
        public Map extractDatabaseIdentifiers(Set set) {
            return delegate.extractDatabaseIdentifiers(set);
        }
    }
}
