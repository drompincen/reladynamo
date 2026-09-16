package io.reladynamo.spike;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraDatedObject;
import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraTransactionalObject;
import com.gs.fw.common.mithra.attribute.update.AttributeUpdateWrapper;
import com.gs.fw.common.mithra.behavior.txparticipation.TxParticipationMode;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.list.cursor.Cursor;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.querycache.CachedQuery;
import com.gs.fw.common.mithra.transaction.BatchUpdateOperation;
import com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister;
import com.gs.fw.common.mithra.transaction.MultiUpdateOperation;
import com.gs.fw.common.mithra.util.Filter;
import com.gs.fw.common.mithra.util.RenewedCacheStats;
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
 * The write-path half of the spike.
 *
 * <p>{@code MithraAbstractObjectPortal} exposes {@code getMithraObjectPersister()} but no matching
 * setter, which leaves an obvious question: does swapping the <em>reader</em> also swap the
 * <em>writer</em>? If it does, one public setter binds both halves and the adapter needs no other
 * seam. If it does not, every write would silently keep going to JDBC while reads came from
 * DynamoDB — the worst possible failure mode, because it looks like it works.
 *
 * <p>This test settles it by observation rather than by reading the field declarations.
 */
class WritePathSpikeTest {

    @BeforeAll
    static void bootReladomo() throws Exception {
        SpikeTestSupport.boot();
    }

    @Test
    void swapping_the_reader_also_swaps_the_persister() {
        MithraAbstractObjectPortal portal =
                (MithraAbstractObjectPortal) SpikeBalanceFinder.getMithraObjectPortal();

        MithraObjectReader original = portal.getDatabaseObject();
        RecordingPersister recording =
                new RecordingPersister((MithraDatedObjectPersister) original, new AtomicInteger());
        try {
            portal.setMithraObjectReader(recording);

            assertThat(portal.getMithraObjectPersister())
                    .as("if the write path does not follow the reader swap, every write silently "
                            + "goes to JDBC while reads come from the adapter")
                    .isSameAs(recording);
        } finally {
            portal.setMithraObjectReader(original);
        }
    }

    /** Delegating persister: proves routing without reimplementing storage. */
    private static final class RecordingPersister implements MithraObjectReader, MithraDatedObjectPersister {
        private final MithraDatedObjectPersister delegate;
        private final AtomicInteger writes;

        RecordingPersister(MithraDatedObjectPersister delegate, AtomicInteger writes) {
            this.delegate = delegate;
            this.writes = writes;
        }

        int writeCount() {
            return writes.get();
        }

        // --- reader side -------------------------------------------------------------------
        @Override public CachedQuery find(AnalyzedOperation o, OrderBy ob, boolean a, int b, int c, boolean d, boolean e) { return delegate.find(o, ob, a, b, c, d, e); }
        @Override public Cursor findCursor(AnalyzedOperation o, Filter f, OrderBy ob, int a, boolean b, int c, boolean d) { return delegate.findCursor(o, f, ob, a, b, c, d); }
        @Override public int count(Operation o) { return delegate.count(o); }
        @Override public List computeFunction(Operation o, OrderBy ob, String s, com.gs.fw.common.mithra.finder.ResultSetParser p) { return delegate.computeFunction(o, ob, s, p); }
        @Override public MithraDataObject refresh(MithraDataObject d, boolean b) { return delegate.refresh(d, b); }
        @Override public MithraDataObject refreshDatedObject(MithraDatedObject o, boolean b) { return delegate.refreshDatedObject(o, b); }
        @Override public List findAggregatedData(Operation o, Map m, Map m1, com.gs.fw.common.mithra.HavingOperation h, boolean b, Class c) { return delegate.findAggregatedData(o, m, m1, h, b, c); }
        @Override public void loadFullCache() { delegate.loadFullCache(); }
        @Override public void reloadFullCache() { delegate.reloadFullCache(); }
        @Override public RenewedCacheStats renewCacheForOperation(Operation o) { return delegate.renewCacheForOperation(o); }
        @Override public Map extractDatabaseIdentifiers(Operation o) { return delegate.extractDatabaseIdentifiers(o); }
        @Override public Map extractDatabaseIdentifiers(Set s) { return delegate.extractDatabaseIdentifiers(s); }

        // --- writer side -------------------------------------------------------------------
        @Override public void update(MithraTransactionalObject o, AttributeUpdateWrapper w) { writes.incrementAndGet(); delegate.update(o, w); }
        @Override public void update(MithraTransactionalObject o, List l) { writes.incrementAndGet(); delegate.update(o, l); }
        @Override public void insert(MithraDataObject d) { writes.incrementAndGet(); delegate.insert(d); }
        @Override public void delete(MithraDataObject d) { writes.incrementAndGet(); delegate.delete(d); }
        @Override public void purge(MithraDataObject d) { writes.incrementAndGet(); delegate.purge(d); }
        @Override public void batchInsert(List l, int i) { writes.incrementAndGet(); delegate.batchInsert(l, i); }
        @Override public void batchDelete(List l) { writes.incrementAndGet(); delegate.batchDelete(l); }
        @Override public void batchDeleteQuietly(List l) { writes.incrementAndGet(); delegate.batchDeleteQuietly(l); }
        @Override public void batchPurge(List l) { writes.incrementAndGet(); delegate.batchPurge(l); }
        @Override public List findForMassDelete(Operation o, boolean b) { return delegate.findForMassDelete(o, b); }
        @Override public void deleteUsingOperation(Operation o) { writes.incrementAndGet(); delegate.deleteUsingOperation(o); }
        @Override public int deleteBatchUsingOperation(Operation o, int i) { writes.incrementAndGet(); return delegate.deleteBatchUsingOperation(o, i); }
        @Override public void batchUpdate(BatchUpdateOperation o) { writes.incrementAndGet(); delegate.batchUpdate(o); }
        @Override public void multiUpdate(MultiUpdateOperation o) { writes.incrementAndGet(); delegate.multiUpdate(o); }
        @Override public void prepareForMassDelete(Operation o, boolean b) { delegate.prepareForMassDelete(o, b); }
        @Override public void prepareForMassPurge(Operation o, boolean b) { delegate.prepareForMassPurge(o, b); }
        @Override public void prepareForMassPurge(List l) { delegate.prepareForMassPurge(l); }
        @Override public void setTxParticipationMode(TxParticipationMode m, com.gs.fw.common.mithra.MithraTransaction t) { delegate.setTxParticipationMode(m, t); }
        @Override public List getForDateRange(MithraDataObject d, Timestamp a, Timestamp b) { return delegate.getForDateRange(d, a, b); }
        @Override public MithraDataObject enrollDatedObject(MithraDatedTransactionalObject o) { return delegate.enrollDatedObject(o); }
    }
}
