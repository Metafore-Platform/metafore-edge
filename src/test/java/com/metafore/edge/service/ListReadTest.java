package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** adr-107 — a list_read is one parameterised SELECT; every name is an identifier and every value is bound. */
class ListReadTest {

    private static Map<String, Object> payload() {
        return Map.of(
            "operation", "list_read",
            "base", Map.of("table", "orders", "alias", "b"),
            "joins", List.of(
                Map.of("table", "ties", "alias", "t1",
                    "on", List.of(Map.of("column", "src", "equals", "b.record_id")),
                    "where", List.of(Map.of("column", "role", "op", "eq", "value", "origin"))),
                Map.of("table", "depots", "alias", "d",
                    "on", List.of(Map.of("column", "record_id", "equals", "t1.dst")))),
            "columns", List.of(Map.of("source", "b.title", "as", "title"), Map.of("source", "d.city", "as", "origin_city")),
            "counts", List.of(Map.of("table", "tasks", "as", "open_tasks",
                "match", List.of(Map.of("column", "order_id", "equals", "b.record_id")),
                "where", List.of(Map.of("column", "status", "op", "in", "value", List.of("open", "in_progress"))))),
            "where", List.of(Map.of("column", "b.status", "op", "ne", "value", "closed")),
            "order_by", List.of(Map.of("column", "b.created_at", "dir", "desc")),
            "exclude_tombstoned", true,
            "limit", 25,
            "offset", 50);
    }

    @Test
    void oneSelectWithJoinsACountAFilterASortAndAPage() {
        ListReadSql r = ListReadSql.render(ListReadRequest.parse(payload()), Set.of("public.orders", "public.tasks"));
        assertEquals("SELECT \"b\".\"title\" AS \"title\", \"d\".\"city\" AS \"origin_city\", "
            + "(SELECT COUNT(*) FROM \"public\".\"tasks\" \"c0\" WHERE \"c0\".\"order_id\" = \"b\".\"record_id\" "
            + "AND \"c0\".\"status\" IN (?, ?) AND \"c0\".\"tombstoned_at\" IS NULL) AS \"open_tasks\" "
            + "FROM \"public\".\"orders\" \"b\" "
            + "LEFT JOIN \"public\".\"ties\" \"t1\" ON \"t1\".\"src\" = \"b\".\"record_id\" AND \"t1\".\"role\" = ? "
            + "LEFT JOIN \"public\".\"depots\" \"d\" ON \"d\".\"record_id\" = \"t1\".\"dst\" "
            + "WHERE \"b\".\"status\" <> ? AND \"b\".\"tombstoned_at\" IS NULL "
            + "ORDER BY \"b\".\"created_at\" DESC NULLS LAST LIMIT ? OFFSET ?", r.sql);
        assertEquals(List.of("open", "in_progress", "origin", "closed"),
            r.binds.stream().map(b -> b.value).toList(), "values bind in placeholder order");
    }

    @Test
    void anAliasMustBeDeclaredBeforeItIsUsed() {
        Map<String, Object> p = new java.util.HashMap<>(payload());
        p.put("joins", List.of(Map.of("table", "depots", "alias", "d",
            "on", List.of(Map.of("column", "record_id", "equals", "t1.dst")))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(p))
            .getMessage().contains("not declared before"));
    }

    @Test
    void aNameThatIsNotAnIdentifierIsRefused() {
        Map<String, Object> p = new java.util.HashMap<>(payload());
        p.put("columns", List.of(Map.of("source", "b.title; DROP TABLE x", "as", "t")));
        assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(p));
        p.put("columns", List.of(Map.of("source", "b.title", "as", "t\" --")));
        assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(p));
    }

    @Test
    void anUnknownOperatorAndAnOversizedPageAreRefused() {
        Map<String, Object> p = new java.util.HashMap<>(payload());
        p.put("where", List.of(Map.of("column", "b.status", "op", "like", "value", "x")));
        assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(p));
        Map<String, Object> q = new java.util.HashMap<>(payload());
        q.put("limit", SqlExecutor.MAX_PAGE_SIZE + 1);
        assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(q));
    }

    @Test
    void aJoinNeedsALink() {
        Map<String, Object> p = new java.util.HashMap<>(payload());
        p.put("joins", List.of(Map.of("table", "ties", "alias", "t1", "on", List.of())));
        assertThrows(IllegalArgumentException.class, () -> ListReadRequest.parse(p));
    }

    @Test
    void aListReadNeedsTheReadCapability() {
        assertEquals("read", Capabilities.deriveRequiredCapability("execute", Map.<String, Object>of("operation", "list_read")));
    }
}
