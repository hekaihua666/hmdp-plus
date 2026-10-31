package com.hmdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 显式本机初始化工具，不启动整个应用；普通 mvn test 不修改数据库。
 * 先备份店铺，再按元数据迁移，最后事务内创建账号和绑定；不会重置已有账号密码或抢占店铺。
 */
@EnabledIfSystemProperty(named = "hmdp.merchant.init", matches = "true")
class MerchantSetupTest {
    @Test
    void initializeMerchantAccounts() throws Exception {
        String passwordA = requiredPassword("MERCHANT_A_PASSWORD");
        String passwordB = requiredPassword("MERCHANT_B_PASSWORD");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yaml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("local", yaml.getObject()));
        try (Connection connection = DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"), environment.getRequiredProperty("spring.datasource.password"))) {
            backupShops(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute(new String(Files.readAllBytes(Paths.get("database/merchant-schema.sql")), StandardCharsets.UTF_8));
                if (!exists(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_shop' AND column_name='owner_id'")) {
                    statement.execute("ALTER TABLE tb_shop ADD COLUMN owner_id BIGINT UNSIGNED NULL COMMENT '商户账号归属'");
                }
                if (!exists(connection, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_shop' AND index_name='uk_shop_owner'")) {
                    statement.execute("ALTER TABLE tb_shop ADD UNIQUE KEY uk_shop_owner (owner_id)");
                }
                if (exists(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_shop' AND column_name IN ('x','y') AND column_type LIKE '%unsigned%'")) {
                    statement.execute("ALTER TABLE tb_shop MODIFY x DOUBLE NOT NULL, MODIFY y DOUBLE NOT NULL");
                }
            }
            // DDL 本身不是可回滚事务；账号创建与归属绑定在一个独立事务中执行。
            connection.setAutoCommit(false);
            try {
                bind(connection, "merchant_a", "商户甲", passwordA, shopId("MERCHANT_A_SHOP_ID", 1));
                bind(connection, "merchant_b", "商户乙", passwordB, shopId("MERCHANT_B_SHOP_ID", 2));
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String requiredPassword(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.length() < 8 || value.length() > 64
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("请通过环境变量 " + variable + " 提供8至64位初始密码");
        }
        return value;
    }

    private static long shopId(String variable, long fallback) {
        String value = System.getenv(variable);
        return value == null ? fallback : Long.parseLong(value);
    }

    private static boolean exists(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next(); return rows.getInt(1) > 0;
        }
    }

    private static void bind(Connection connection, String username, String name, String password, long shopId) throws SQLException {
        long merchantId;
        try (PreparedStatement find = connection.prepareStatement("SELECT id FROM tb_merchant WHERE username=?")) {
            find.setString(1, username);
            try (ResultSet rows = find.executeQuery()) {
                if (rows.next()) merchantId = rows.getLong(1);
                else {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO tb_merchant(username,password_hash,name,status) VALUES(?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, username); insert.setString(2, new BCryptPasswordEncoder().encode(password)); insert.setString(3, name);
                        insert.executeUpdate();
                        try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); merchantId = keys.getLong(1); }
                    }
                }
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT owner_id FROM tb_shop WHERE id=? FOR UPDATE")) {
            select.setLong(1, shopId);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) throw new SQLException("绑定目标店铺不存在：" + shopId);
                Long owner = rows.getObject(1) == null ? null : rows.getLong(1);
                if (owner != null && owner != merchantId) throw new SQLException("目标店铺已归属其他商户，禁止覆盖：" + shopId);
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE tb_shop SET owner_id=? WHERE id=? AND (owner_id IS NULL OR owner_id=?)")) {
            update.setLong(1, merchantId); update.setLong(2, shopId); update.setLong(3, merchantId);
            update.executeUpdate();
        }
    }

    private static void backupShops(Connection connection) throws Exception {
        List<Map<String, Object>> backup = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM tb_shop ORDER BY id")) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i=1; i<=metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i), rows.getObject(i));
                backup.add(row);
            }
        }
        Files.createDirectories(Paths.get("target"));
        // 不覆盖第一次迁移前的恢复点；运行产物已被 Git 忽略。
        Path destination = Paths.get("target/merchant-shops-before.json");
        if (!Files.exists(destination)) new ObjectMapper().writeValue(destination.toFile(), backup);
    }
}
