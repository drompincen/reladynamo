package com.reladynamo.demo.classifier.util;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.TransactionalCommand;

import java.sql.Timestamp;

/**
 * Runs Reladomo work, optionally at a caller-supplied processing time so seed/demo
 * data is deterministic.
 */
public final class Transactions
{
    private Transactions()
    {
    }

    public static <T> T run(TransactionalCommand<T> command)
    {
        return MithraManagerProvider.getMithraManager().executeTransactionalCommand(command);
    }

    public static void run(final Runnable action)
    {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(new TransactionalCommand<Void>()
        {
            @Override
            public Void executeTransaction(MithraTransaction tx)
            {
                action.run();
                return null;
            }
        });
    }

    public static void at(final Timestamp processingTime, final Runnable action)
    {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(new TransactionalCommand<Void>()
        {
            @Override
            public Void executeTransaction(MithraTransaction tx)
            {
                tx.setProcessingStartTime(processingTime.getTime());
                action.run();
                return null;
            }
        });
    }
}
