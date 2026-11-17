package cn.sunline.tss.compare;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

record SparkJobArtifact(Path path,String sha256) {
    private static final String RESOLVER_CLASS="cn/sunline/tss/tssveri/spark/QueryFieldResolver.class";

    static SparkJobArtifact inspect(String configuredPath,String mainClass,boolean matchByName)
            throws IOException,NoSuchAlgorithmException {
        Path configured=Path.of(configuredPath);
        if(!Files.isRegularFile(configured)) {
            throw new IllegalArgumentException("app_resource 指定的作业包不存在："+configured.toAbsolutePath().normalize());
        }
        Path path=configured.toRealPath();
        if(matchByName) {
            try(var jar=new JarFile(path.toFile())) {
                if(jar.getJarEntry(mainClass.replace('.','/')+".class")==null) {
                    throw new IllegalArgumentException("app_resource="+path+" 缺少主类 "+mainClass+"，请部署配套的 Spark 作业 JAR");
                }
                if(jar.getJarEntry(RESOLVER_CLASS)==null) {
                    throw new IllegalArgumentException("app_resource="+path+" 缺少 QueryFieldResolver，名称匹配模式需要更新 Spark 作业 JAR");
                }
            }
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=new DigestInputStream(Files.newInputStream(path),digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return new SparkJobArtifact(path,HexFormat.of().formatHex(digest.digest()));
    }
}
