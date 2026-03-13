package com.herald.ui.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String SELECT_COLUMNS =
            "SELECT id, role, content, tool_calls AS toolCalls, created_at AS timestamp FROM messages";

    public List<Map<String, Object>> listRecent(int limit) {
        return jdbcTemplate.queryForList(SELECT_COLUMNS + " ORDER BY id DESC LIMIT ?", limit);
    }

    public List<Map<String, Object>> listFiltered(String search, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS + " WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            sql.append(" AND content LIKE ?");
            params.add("%" + search.trim() + "%");
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startDate + "T00:00:00");
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endDate + "T23:59:59");
        }

        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    Map<String, Object> getById(long id) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                SELECT_COLUMNS + " WHERE id = ?", id);
        return results.isEmpty() ? null : results.get(0);
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM messages");
    }

    int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Integer.class);
        return count != null ? count : 0;
    }
}
