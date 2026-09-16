package com.reladynamo.demo.crm.util;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.TransactionalCommand;

import java.sql.Timestamp;

/**
 * Runs Reladomo work at a caller-supplied processing time so seed/demo data is deterministic.
 */
public final class Transactions
{
    private Transactions()
    {
    }

    public static void at(final Timestamp processingTime, final Runnable action)
    {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(new TransactionalCommand<Void>()
        {
            @Override
            public Void executeTransaction(MithraTransaction tx) throws Throwable
            {
                tx.setProcessingStartTime(processingTime.getTime());
                action.run();
                return null;
            }
        });
    }
}
