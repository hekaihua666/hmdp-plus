package com.hmdp.utils;

import java.nio.file.Paths;

public class SystemConstants {
    /**
     * 图片目录必须与 Nginx 的静态目录保持一致。
     * 优先读环境变量 IMAGES_DIR（后端容器化时指向共享卷）；没配就退回项目目录拼接，
     * 保持宿主机 IDEA 里直接跑时的老行为不变。
     */
    public static final String IMAGE_UPLOAD_DIR = resolveUploadDir();

    /** 先看环境变量有没有指定图片目录，没有就用项目目录，保证两种运行方式都不改原逻辑。 */
    private static String resolveUploadDir() {
        String configured = System.getenv("IMAGES_DIR");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Paths.get(
                System.getProperty("user.dir"),
                "src", "main", "resources", "nginx-1.18.0", "html", "hmdp", "imgs"
        ).toString();
    }
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
