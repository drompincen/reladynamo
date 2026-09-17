package com.reladynamo.demo.petstore.store;

import com.reladynamo.demo.petstore.runtime.H2PetstoreConnectionManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads physical H2 rows (bypassing Reladomo finders) so tests can prove a non-audited correction
 * actually deleted the prior value rather than hiding it behind an as-of query.
 */
public final class PhysicalStore {

    private PhysicalStore() {
    }

    public record ProductRow(BigDecimal unitPrice, Timestamp fromZ, Timestamp thruZ) {}

    public record StockRow(int quantityOnHand, Timestamp fromZ, Timestamp thruZ) {}

    public record SalesOrderRow(
            String status, BigDecimal totalAmount, Timestamp inZ, Timestamp outZ) {}

    public record PetRow(String name, String status, Timestamp fromZ, Timestamp thruZ) {}

    public record EmployeeRow(String lastName, String role, Timestamp fromZ, Timestamp thruZ) {}

    public static List<ProductRow> productRows(long productId) {
        return query(
                "SELECT UNIT_PRICE, FROM_Z, THRU_Z FROM PRODUCT WHERE PRODUCT_ID = ? ORDER BY FROM_Z",
                productId,
                rs -> new ProductRow(rs.getBigDecimal(1), rs.getTimestamp(2), rs.getTimestamp(3)));
    }

    public static List<StockRow> stockRows(long stockId) {
        return query(
                "SELECT QUANTITY_ON_HAND, FROM_Z, THRU_Z FROM STOCK_LEVEL WHERE STOCK_ID = ? ORDER BY FROM_Z",
                stockId,
                rs -> new StockRow(rs.getInt(1), rs.getTimestamp(2), rs.getTimestamp(3)));
    }

    public static List<SalesOrderRow> salesOrderRows(long orderId) {
        return query(
                "SELECT STATUS, TOTAL_AMOUNT, IN_Z, OUT_Z FROM SALES_ORDER WHERE ORDER_ID = ? ORDER BY IN_Z",
                orderId,
                rs -> new SalesOrderRow(
                        rs.getString(1), rs.getBigDecimal(2), rs.getTimestamp(3), rs.getTimestamp(4)));
    }

    public static List<PetRow> petRows(long petId) {
        return query(
                "SELECT NAME, STATUS, FROM_Z, THRU_Z FROM PET WHERE PET_ID = ? ORDER BY FROM_Z",
                petId,
                rs -> new PetRow(rs.getString(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4)));
    }

    public static List<EmployeeRow> employeeRows(long employeeId) {
        return query(
                "SELECT LAST_NAME, ROLE, FROM_Z, THRU_Z FROM EMPLOYEE WHERE EMPLOYEE_ID = ? ORDER BY FROM_Z",
                employeeId,
                rs -> new EmployeeRow(
                        rs.getString(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4)));
    }

    public static int countAllDomainRows() {
        String sql =
                """
                SELECT SUM(C) FROM (
                    SELECT COUNT(*) C FROM SPECIES
                    UNION ALL SELECT COUNT(*) FROM BREED
                    UNION ALL SELECT COUNT(*) FROM STORE
                    UNION ALL SELECT COUNT(*) FROM KENNEL
                    UNION ALL SELECT COUNT(*) FROM PRODUCT_CATEGORY
                    UNION ALL SELECT COUNT(*) FROM PRODUCT
                    UNION ALL SELECT COUNT(*) FROM PET
                    UNION ALL SELECT COUNT(*) FROM FEEDING_SCHEDULE
                    UNION ALL SELECT COUNT(*) FROM VETERINARY_VISIT
                    UNION ALL SELECT COUNT(*) FROM VACCINATION
                    UNION ALL SELECT COUNT(*) FROM GROOMING_APPOINTMENT
                    UNION ALL SELECT COUNT(*) FROM STOCK_LEVEL
                    UNION ALL SELECT COUNT(*) FROM PET_OWNER
                    UNION ALL SELECT COUNT(*) FROM SALES_ORDER
                    UNION ALL SELECT COUNT(*) FROM SALES_ORDER_LINE
                    UNION ALL SELECT COUNT(*) FROM PAYMENT
                    UNION ALL SELECT COUNT(*) FROM SUPPLIER
                    UNION ALL SELECT COUNT(*) FROM PURCHASE_ORDER
                    UNION ALL SELECT COUNT(*) FROM PURCHASE_ORDER_LINE
                    UNION ALL SELECT COUNT(*) FROM SHIPMENT
                    UNION ALL SELECT COUNT(*) FROM ADOPTION
                    UNION ALL SELECT COUNT(*) FROM EMPLOYEE
                )
                """;
        try (Connection connection = H2PetstoreConnectionManager.getInstance().getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to count domain rows", e);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static <T> List<T> query(String sql, long id, RowMapper<T> mapper) {
        List<T> rows = new ArrayList<>();
        try (Connection connection = H2PetstoreConnectionManager.getInstance().getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("physical query failed: " + sql, e);
        }
        return rows;
    }
}
