package kr.lit.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@Configuration(proxyBeanMethods = false)
class JdbcConfiguration {
    @Bean
    JdbcTemplate jdbcTemplate(DataSource source, JdbcProperties properties) {
        var template = new JdbcTemplate(source) {
            @Override
            protected RowMapper<Map<String, Object>> getColumnMapRowMapper() {
                return new ColumnMapRowMapper() {
                    @Override
                    protected Object getColumnValue(ResultSet result, int index) throws SQLException {
                        // PostgreSQL's untyped getObject returns Timestamp for timestamptz.
                        if ("timestamptz".equals(result.getMetaData().getColumnTypeName(index))) {
                            return result.getObject(index, OffsetDateTime.class);
                        }
                        return super.getColumnValue(result, index);
                    }
                };
            }
        };
        var settings = properties.getTemplate();
        template.setFetchSize(settings.getFetchSize());
        template.setMaxRows(settings.getMaxRows());
        if (settings.getQueryTimeout() != null) {
            template.setQueryTimeout((int) settings.getQueryTimeout().getSeconds());
        }
        return template;
    }
}
