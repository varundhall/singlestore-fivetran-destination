package com.singlestore.fivetran.destination.connector.writers;

import com.google.protobuf.ByteString;
import com.singlestore.fivetran.destination.connector.JDBCUtil;

import com.singlestore.fivetran.destination.connector.warning_util.WarningHandler;
import fivetran_sdk.v2.Column;
import fivetran_sdk.v2.FileParams;
import fivetran_sdk.v2.DataType;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadDataWriter<T> extends Writer {
    private static final Logger logger = LoggerFactory.getLogger(JDBCUtil.class);

    final int BUFFER_SIZE = 524288;

    List<Column> headerColumns;
    PipedOutputStream outputStream;
    PipedInputStream inputStream;
    Thread t;
    final SQLException[] queryException = new SQLException[1];
    Statement stmt;
    WarningHandler<T> warningHandler;

    public LoadDataWriter(Connection conn, String database, String table, List<Column> columns,
                          FileParams params, Map<String, ByteString> secretKeys, Integer batchSize,
                          WarningHandler<T> warningHandler)
            throws IOException {
        super(conn, database, table, columns, params, secretKeys, batchSize);
        this.warningHandler = warningHandler;
    }

    private String tmpColumnName(String name) {
        return String.format("@%s", name);
    }

    @Override
    public void setHeader(List<String> header) throws SQLException, IOException {
        outputStream = new PipedOutputStream();
        inputStream = new PipedInputStream(outputStream, BUFFER_SIZE);
        headerColumns = new ArrayList<>();
        queryException[0] = null;

        Map<String, Column> nameToColumn = new HashMap<>();
        for (Column column : columns) {
            nameToColumn.put(column.getName(), column);
        }

        for (String name : header) {
            headerColumns.add(nameToColumn.get(name));
        }

        List<Column> binaryColumns = headerColumns.stream()
                .filter(column -> column.getType() == DataType.BINARY).collect(Collectors.toList());

        // TODO: PLAT-6898 add compression
        String query = String.format(
                "LOAD DATA LOCAL INFILE '###.tsv' REPLACE INTO TABLE %s (%s) NULL DEFINED BY %s %s",
                JDBCUtil.escapeTable(database, table), headerColumns.stream().map(c -> {
                    String escapedName = JDBCUtil.escapeIdentifier(c.getName());
                    if (c.getType() == DataType.BINARY) {
                        return tmpColumnName(escapedName);
                    }
                    return escapedName;
                }).collect(Collectors.joining(", ")), JDBCUtil.escapeString(params.getNullString()),
                binaryColumns.isEmpty() ? "" : "SET " + binaryColumns.stream().map(column -> {
                    String escapedName = JDBCUtil.escapeIdentifier(column.getName());
                    return String.format("%s = FROM_BASE64(%s)", escapedName,
                            tmpColumnName(escapedName));
                }).collect(Collectors.joining(", ")));

        stmt = conn.createStatement();
        ((com.singlestore.jdbc.Statement) stmt).setNextLocalInfileInputStream(inputStream);

        t = new Thread(() -> {
            try {
                stmt.executeUpdate(query);
                stmt.close();
            } catch (SQLException e) {
                warningHandler.handle("Failed to execute LOAD DATA query", e);

                queryException[0] = e;
            }
        });
        t.start();
    }

    @Override
    public void writeRow(List<String> row) throws Exception {
        try {
            for (int i = 0; i < row.size(); i++) {
                String value = row.get(i);

                DataType type = headerColumns.get(i).getType();
                if (type == DataType.BOOLEAN) {
                    if (value.equalsIgnoreCase("true")) {
                        value = "1";
                    } else if (value.equalsIgnoreCase("false")) {
                        value = "0";
                    }
                } else if ((type == DataType.NAIVE_DATETIME || type == DataType.UTC_DATETIME)
                        && !value.equals(params.getNullString())) {
                    value = JDBCUtil.formatISODateTime(value);
                }

                if (value.indexOf('\\') != -1) {
                    value = value.replace("\\", "\\\\");
                }
                if (value.indexOf('\n') != -1) {
                    value = value.replace("\n", "\\n");
                }
                if (value.indexOf('\t') != -1) {
                    value = value.replace("\t", "\\t");
                }

                outputStream.write(value.getBytes());

                if (i != row.size() - 1) {
                    outputStream.write('\t');
                } else {
                    outputStream.write('\n');
                }
            }
        } catch (Exception e) {
            warningHandler.handle("Failed to write TSV data to stream", e);

            abort(e);
        }
    }

    @Override
    public void commit() throws InterruptedException, IOException, SQLException {
        if (t == null) {
            // nothing is written
            return;
        }

        outputStream.close();
        t.join();

        if (queryException[0] != null) {
            throw queryException[0];
        }
    }

    private void abort(Exception writerException) throws Exception {
        try {
            outputStream.close();
        } catch (Exception e) {
            warningHandler.handle("Failed to close the stream during the abort", e);
        } finally {
            try {
                stmt.cancel();
            } catch (Exception e) {
                warningHandler.handle("Failed to cancel the statement during the abort", e);
            } finally {
                try {
                    t.interrupt();
                } catch (Exception e) {
                    warningHandler.handle("Failed to interrupt the thread during the abort", e);
                }
            }
        }

        if (writerException instanceof IOException
                && writerException.getMessage().contains("Pipe closed")) {
            // The actual exception occurred in the query thread
            throw queryException[0];
        } else {
            throw writerException;
        }
    }
}
