package com.hmdp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;

/** 显式初始化本机平台，普通测试不改数据库、不重置已有管理员密码或商户状态。 */
@EnabledIfSystemProperty(named="hmdp.platform.init",matches="true")
class PlatformSetupTest {
    @Test void initializePlatform() throws Exception {
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yaml"));
        StandardEnvironment env=new StandardEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("local",yaml.getObject()));
        try(Connection connection=DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"))) {
            try(Statement sql=connection.createStatement()) {
                sql.execute(new String(Files.readAllBytes(Paths.get("database/platform-schema.sql")),StandardCharsets.UTF_8));
                // DDL不是可回滚事务；先查元数据，仅给旧表补缺失的版本列。
                try(ResultSet rows=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_merchant' AND column_name='session_version'")) {
                    rows.next();
                    if(rows.getInt(1)==0) {
                        try(Statement alter=connection.createStatement()) {
                            alter.execute("ALTER TABLE tb_merchant ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 COMMENT '封禁时递增，解封不恢复旧token'");
                        }
                    }
                }
            }
            // 只在用户名不存在时创建本机教学账号；重跑保留已有密码与停用状态。
            try(PreparedStatement find=connection.prepareStatement("SELECT id FROM tb_admin WHERE username=?")) {
                find.setString(1,"admin");
                try(ResultSet rows=find.executeQuery()) {
                    if(!rows.next()) {
                        String password=System.getenv("PLATFORM_ADMIN_PASSWORD");
                        if(password==null) password="123456";
                        if(password.length()<6 || password.length()>64 || password.getBytes(StandardCharsets.UTF_8).length>72) {
                            throw new IllegalArgumentException("初始管理员密码须为6至64位且不超过72个UTF-8字节");
                        }
                        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO tb_admin(username,password_hash,status) VALUES(?,?,1)")) {
                            insert.setString(1,"admin");insert.setString(2,new BCryptPasswordEncoder().encode(password));insert.executeUpdate();
                        }
                    }
                }
            }
        }
    }
}
