package cn.sunline.tss.compare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompareApplication {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--encrypt-password".equals(args[0])) {
            System.out.println(MetadataPasswordCipher.encryptFromStdin());
            return;
        }
        SpringApplication.run(CompareApplication.class,args);
    }
}
