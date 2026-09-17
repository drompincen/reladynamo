package com.reladynamo.demo.crm.util;

import java.io.PrintStream;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Prints as-of query results as a small table so the bitemporal rectangle is visible.
 */
public final class AsOfPrinter
{
    private AsOfPrinter()
    {
    }

    public static String ts(Timestamp timestamp)
    {
        if (timestamp == null)
        {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(timestamp);
    }

    public static void section(PrintStream out, String title)
    {
        out.println();
        out.println("================================================================================");
        out.println(title);
        out.println("================================================================================");
    }

    public static void table(PrintStream out, List<String> headers, List<List<String>> rows)
    {
        int columns = headers.size();
        int[] widths = new int[columns];
        for (int i = 0; i < columns; i++)
        {
            widths[i] = headers.get(i).length();
        }
        for (int r = 0; r < rows.size(); r++)
        {
            List<String> row = rows.get(r);
            for (int i = 0; i < columns; i++)
            {
                String cell = i < row.size() && row.get(i) != null ? row.get(i) : "";
                if (cell.length() > widths[i])
                {
                    widths[i] = cell.length();
                }
            }
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < columns; i++)
        {
            if (i > 0)
            {
                line.append(" | ");
            }
            line.append(pad(headers.get(i), widths[i]));
        }
        out.println(line);
        line.setLength(0);
        for (int i = 0; i < columns; i++)
        {
            if (i > 0)
            {
                line.append("-+-");
            }
            for (int w = 0; w < widths[i]; w++)
            {
                line.append('-');
            }
        }
        out.println(line);
        if (rows.isEmpty())
        {
            out.println("(no rows)");
            return;
        }
        for (int r = 0; r < rows.size(); r++)
        {
            List<String> row = rows.get(r);
            line.setLength(0);
            for (int i = 0; i < columns; i++)
            {
                if (i > 0)
                {
                    line.append(" | ");
                }
                String cell = i < row.size() && row.get(i) != null ? row.get(i) : "";
                line.append(pad(cell, widths[i]));
            }
            out.println(line);
        }
    }

    public static List<String> row(String... cells)
    {
        List<String> list = new ArrayList<String>(cells.length);
        for (int i = 0; i < cells.length; i++)
        {
            list.add(cells[i] == null ? "" : cells[i]);
        }
        return list;
    }

    private static String pad(String value, int width)
    {
        if (value.length() >= width)
        {
            return value;
        }
        StringBuilder builder = new StringBuilder(width);
        builder.append(value);
        while (builder.length() < width)
        {
            builder.append(' ');
        }
        return builder.toString();
    }
}
