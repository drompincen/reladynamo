package io.reladynamo.ddb.persist;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.MithraUniqueIndexViolationException;
import com.gs.fw.common.mithra.TransactionLifeCycleListener;
import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import com.gs.fw.common.mithra.transaction.LocalTx;
import com.gs.fw.common.mithra.transaction.TransactionLocal;
import com.gs.fw.common.mithra.util.InternalList;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

import javax.transaction.Synchronization;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One Reladomo root transaction, one DynamoDB client, one atomic request across all entity tables. */
public final class DynamoDbTransactionCoordinator {

    private static final TransactionLocal LOCAL = new TransactionLocal();
    // Identity is deliberate: clients with different credentials/endpoints may never share a request.
    private static final Map<DynamoDbClient, DynamoDbCommitOutcomeUnknownException> UNKNOWN = new IdentityHashMap<>();
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private DynamoDbTransactionCoordinator() {
    }

    public static boolean isActive() {
        return current() != null;
    }

    private static MithraTransaction current() {
        return MithraManagerProvider.getMithraManager().getCurrentTransaction();
    }

    public static void submit(DynamoDbClient client, PhysicalWrite action) {
        submit(client, action, null);
    }

    static void submit(DynamoDbClient client, PhysicalWrite action, MithraObjectPortal portal) {
        assertUsable(client);
        MithraTransaction tx = current();
        if (tx == null) {
            action.writeImmediately(client);
            return;
        }
        while (tx.getParent() != null) {
            tx = tx.getParent();
        }
        State state = (State) LOCAL.get(tx);
        if (state == null) {
            state = new State(tx, client);
            try {
                tx.enlistResource(state);
            } catch (Exception e) {
                throw new DynamoDbTransactionException(
                        "RELADYNAMO-TXN-005", "cannot enlist DynamoDB participant", e);
            }
            LOCAL.set(tx, state);
            State enrolled = state;
            tx.registerLifeCycleListener(new TransactionLifeCycleListener() {
                @Override
                public void beforeCommit() {
                    enrolled.validate();
                }

                @Override
                public void beforeRollback() {
                }
            });
            tx.registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    enrolled.validate();
                }

                @Override
                public void afterCompletion(int status) {
                    try {
                        if (enrolled.unknown != null) {
                            for (MithraObjectPortal enrolledPortal : enrolled.portals) {
                                try {
                                    enrolledPortal.clearQueryCache();
                                    enrolledPortal.getCache().clear();
                                } catch (RuntimeException e) {
                                    enrolled.unknown.addSuppressed(e);
                                }
                            }
                        }
                    } finally {
                        enrolled.actions.clear();
                        LOCAL.remove(enrolled.tx);
                    }
                }
            });
        }
        if (state.client != client) {
            state.refuse("RELADYNAMO-TXN-005",
                    "multiple DynamoDB clients in one logical transaction; share one client across tables");
        }
        if (portal != null) {
            state.portals.add(portal);
        }
        state.add(action);
    }

    static synchronized void assertUsable(DynamoDbClient client) {
        DynamoDbCommitOutcomeUnknownException unknown = UNKNOWN.get(client);
        if (unknown != null) {
            throw unknown;
        }
    }

    /**
     * Test isolation seam. Production code must never call this: a quarantined client stays
     * unusable until the process is replaced.
     */
    static synchronized void forgetUnknownOutcome(DynamoDbClient client) {
        UNKNOWN.remove(client);
    }

    /** Cache hits retain Reladomo's own transactional view; database fall-through must not lie. */
    static void beforeRead(DynamoDbClient client) {
        assertUsable(client);
        MithraTransaction tx = current();
        if (tx == null) {
            return;
        }
        while (tx.getParent() != null) {
            tx = tx.getParent();
        }
        State state = (State) LOCAL.get(tx);
        if (state != null && !state.actions.isEmpty()) {
            throw new DynamoDbTransactionException("RELADYNAMO-TXN-006",
                    "database reads with staged writes are unsupported; use enrolled objects or read after commit");
        }
    }

    private static final class State implements XAResource {
        final MithraTransaction tx;
        final DynamoDbClient client;
        final LocalTx jta;
        final LinkedHashMap<Object, PhysicalWrite> actions = new LinkedHashMap<>();
        DynamoDbTransactionException rejection;
        boolean committed;
        DynamoDbCommitOutcomeUnknownException unknown;
        final Set<MithraObjectPortal> portals = Collections.newSetFromMap(new IdentityHashMap<>());

        State(MithraTransaction tx, DynamoDbClient client) {
            this.tx = tx;
            this.client = client;
            try {
                javax.transaction.Transaction actual =
                        MithraManagerProvider.getMithraManager().getJtaTransactionManager().getTransaction();
                if (actual == null || actual.getClass() != LocalTx.class) {
                    throw new IllegalStateException("only Reladomo 18.1.0 LocalTx is supported");
                }
                this.jta = (LocalTx) actual;
                if (resourceCount() != 0) {
                    throw new IllegalStateException("another durable resource is already enlisted");
                }
            } catch (Exception e) {
                throw new DynamoDbTransactionException(
                        "RELADYNAMO-TXN-005", "unsupported or mixed transaction manager", e);
            }
        }

        void add(PhysicalWrite action) {
            if (rejection != null) {
                throw rejection;
            }
            try {
                PhysicalWrite old = actions.get(action.identity());
                actions.put(action.identity(), old == null ? action : old.then(action));
            } catch (DynamoDbTransactionException e) {
                rejection = e;
                tx.expectRollbackWithCause(e);
                throw e;
            }
        }

        void refuse(String code, String message) {
            rejection = new DynamoDbTransactionException(code, message);
            tx.expectRollbackWithCause(rejection);
            throw rejection;
        }

        int resourceCount() throws ReflectiveOperationException {
            // LocalTx uses last-resource optimization and SKIPS prepare on its first resource.
            // Public XA.prepare alone cannot reject mixed resources. Pinned, fail-closed guard.
            Field field = LocalTx.class.getDeclaredField("resourceManagers");
            field.setAccessible(true);
            return ((InternalList) field.get(jta)).size();
        }

        void validate() {
            if (rejection != null) {
                throw rejection;
            }
            try {
                if (resourceCount() != 1) {
                    refuse("RELADYNAMO-TXN-005",
                            "mixed durable resources cannot commit atomically with DynamoDB");
                }
            } catch (ReflectiveOperationException e) {
                throw new DynamoDbTransactionException(
                        "RELADYNAMO-TXN-005", "cannot verify sole resource", e);
            }
            if (actions.size() > 100) {
                refuse("RELADYNAMO-TXN-001",
                        "transaction has " + actions.size() + " distinct actions; maximum is 100");
            }
            long bytes = 0;
            for (PhysicalWrite action : actions.values()) {
                bytes += action.itemBytes();
            }
            if (bytes > MAX_BYTES) {
                refuse("RELADYNAMO-TXN-002",
                        "transaction item size is " + bytes + " bytes; maximum is " + MAX_BYTES);
            }
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            if (!onePhase) {
                throw xa("RELADYNAMO-TXN-005: two-phase commit is unsupported", XAException.XA_RBROLLBACK);
            }
            validate();
            if (committed || actions.isEmpty()) {
                return;
            }
            // Snapshot in TransactItems order (LinkedHashMap insertion). Translation walks this
            // list by index so the surfaced exception cannot depend on HashMap iteration.
            List<PhysicalWrite> submitted = new ArrayList<PhysicalWrite>(actions.values());
            List<TransactWriteItem> items = new ArrayList<>();
            for (int i = 0; i < submitted.size(); i++) {
                items.add(submitted.get(i).transactionItem());
            }
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(items)
                    .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofSeconds(10))
                            .apiCallAttemptTimeout(Duration.ofSeconds(3))
                            .build())
                    .build();
            try {
                commitRequest(client, request, submitted);
            } catch (DynamoDbCommitOutcomeUnknownException e) {
                unknown = e;
                throw e;
            }
            committed = true;
        }

        @Override
        public int prepare(Xid xid) throws XAException {
            throw xa("RELADYNAMO-TXN-005: two-phase prepare is unsupported", XAException.XA_RBROLLBACK);
        }

        @Override
        public void rollback(Xid xid) {
            actions.clear();
        }

        @Override
        public void start(Xid xid, int flags) {
        }

        @Override
        public void end(Xid xid, int flags) {
        }

        @Override
        public boolean isSameRM(XAResource other) {
            return this == other;
        }

        @Override
        public void forget(Xid xid) {
        }

        @Override
        public Xid[] recover(int flag) {
            return new Xid[0];
        }

        @Override
        public int getTransactionTimeout() {
            return 0;
        }

        @Override
        public boolean setTransactionTimeout(int seconds) {
            return false;
        }
    }

    private static XAException xa(String message, int code) {
        XAException e = new XAException(message);
        e.errorCode = code;
        return e;
    }

    private static void commitRequest(DynamoDbClient client, TransactWriteItemsRequest request,
                                      List<PhysicalWrite> submitted) {
        long started = System.nanoTime();
        RuntimeException last = null;
        boolean ambiguous = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            // Never restart the ten-minute token window. Ten seconds per call, at most four calls.
            if (System.nanoTime() - started > Duration.ofMinutes(8).toNanos()) {
                break;
            }
            try {
                client.transactWriteItems(request);
                return;
            } catch (TransactionCanceledException e) {
                last = e;
                if (!retryableCancellation(e)) {
                    if (ambiguous) {
                        break;
                    }
                    RuntimeException conflict = translateConditionConflict(e, submitted);
                    if (conflict != null) {
                        throw conflict;
                    }
                    throw new DynamoDbTransactionException(
                            "RELADYNAMO-TXN-004", "transaction canceled without retryable reasons", e);
                }
            } catch (TransactionInProgressException | SdkClientException e) {
                last = e;
                ambiguous = true;
            } catch (DynamoDbException e) {
                last = e;
                if (e.statusCode() < 500) {
                    if (ambiguous) {
                        break;
                    }
                    throw new DynamoDbTransactionException("RELADYNAMO-TXN-004", "transaction rejected", e);
                }
                ambiguous = true;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(attempt == 2 ? 5000L : 100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = new RuntimeException(e);
                    break;
                }
            }
        }
        if (ambiguous) {
            DynamoDbCommitOutcomeUnknownException unknown =
                    new DynamoDbCommitOutcomeUnknownException(request, started, last);
            synchronized (DynamoDbTransactionCoordinator.class) {
                UNKNOWN.put(client, unknown);
            }
            throw unknown;
        }
        throw new DynamoDbTransactionException(
                "RELADYNAMO-TXN-004", "retryable cancellations exhausted; no successful commit", last);
    }

    private static boolean retryableCancellation(TransactionCanceledException e) {
        boolean retryable = false;
        for (CancellationReason reason : e.cancellationReasons()) {
            String code = reason.code();
            if ("None".equals(code)) {
                continue;
            }
            if (!Arrays.asList("TransactionConflict", "ProvisionedThroughputExceeded", "ThrottlingError")
                    .contains(code)) {
                return false;
            }
            retryable = true;
        }
        return retryable;
    }

    /**
     * Maps a cancellation to a Reladomo conflict only when every non-{@code None} reason is
     * {@code ConditionalCheckFailed} and each failed slot lines up with a submitted action whose
     * condition is an insert-not-exists or expected-prior-state predicate. Unaligned reason lists
     * (the TXN-004 characterisation) and unclassified conditions stay TXN-004.
     *
     * <p>Several condition failures: the first failed action in TransactItems order wins.
     */
    private static RuntimeException translateConditionConflict(TransactionCanceledException e,
                                                               List<PhysicalWrite> submitted) {
        List<CancellationReason> reasons = e.cancellationReasons();
        if (reasons == null || submitted == null || reasons.size() != submitted.size()) {
            return null;
        }
        RuntimeException first = null;
        for (int i = 0; i < submitted.size(); i++) {
            CancellationReason reason = reasons.get(i);
            String code = reason == null ? null : reason.code();
            if (code == null || code.isEmpty() || "None".equals(code)) {
                continue;
            }
            if (!"ConditionalCheckFailed".equals(code)) {
                return null;
            }
            RuntimeException mapped = mithraConflictFor(submitted.get(i), e);
            if (mapped == null) {
                return null;
            }
            if (first == null) {
                first = mapped;
            }
        }
        return first;
    }

    private static RuntimeException mithraConflictFor(PhysicalWrite action,
                                                      TransactionCanceledException cause) {
        if (action.isInsertNotExistsCondition()) {
            return new MithraUniqueIndexViolationException(
                    "duplicate insert of " + action.table() + " " + describeKey(action.itemKey())
                            + ": an item with this key already exists",
                    cause);
        }
        if (action.isExpectedPriorStateCondition()) {
            MithraOptimisticLockException ex = new MithraOptimisticLockException(
                    "optimistic lock failed on " + (action.isDelete() ? "delete" : "update")
                            + " of " + action.table() + " " + describeKey(action.itemKey())
                            + "; the stored item did not match the expected prior state",
                    cause);
            ex.setRetriable(true);
            return ex;
        }
        return null;
    }

    private static String describeKey(Map<String, AttributeValue> key) {
        if (key == null) {
            return "pk=<null> sk=<null>";
        }
        return "pk=" + s(key.get("pk")) + " sk=" + s(key.get("sk"));
    }

    private static String s(AttributeValue v) {
        return v == null ? "<null>" : v.s();
    }
}
