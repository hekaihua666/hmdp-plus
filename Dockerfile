# HMDP 后端镜像：多阶段构建。
#   阶段一：Maven 打包（编译目标 Java 8，用 JDK 17 编译，与本机验证环境一致）；
#   阶段二：只放 JRE + 打好的 fat jar，镜像更小、不带源码和构建依赖。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先只拷 pom.xml 拉依赖，让"依赖没变"时复用 Docker 层缓存，不反复下载。
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:resolve dependency:resolve-plugins
# 再拷源码打包（-DskipTests：容器内无测试依赖的 MySQL/Redis/ES，只编不测）。
COPY src ./src
RUN cp src/main/resources/application.example.yaml src/main/resources/application.yaml && mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/hmdp-1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
