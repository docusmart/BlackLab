package nl.inl.blacklab.server;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

public class Main {
    public static void main(String[] args) throws LifecycleException {
        var tomcat = new Tomcat();
        tomcat.setPort(9000);
        tomcat.addWebapp("/", new File("src/main/java").getAbsolutePath());
        tomcat.start();
        tomcat.getServer().await();

    }
}
