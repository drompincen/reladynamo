package com.reladynamo.demo.petstore.runtime;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.TransactionalCommand;

public final class Tx {

    private Tx() {
    }

    public static <T> T run(TransactionalCommand<T> command) {
        return MithraManagerProvider.getMithraManager().executeTransactionalCommand(command);
    }

    /** Run {@code command} with a deterministic Reladomo processing-start time (audit-only). */
    public static <T> T runAt(java.sql.Timestamp processingTime, TransactionalCommand<T> command) {
        return run(tx -> {
            tx.setProcessingStartTime(processingTime.getTime());
            return command.executeTransaction(tx);
        });
    }
}
