# Packaging guide — `[projectname]-assembly`

How to package a Java app as a **fat jar** (classes at root, dependency jars under `/lib`) using `maven-assembly-plugin`. Drop this module into a multi-module Maven project. The output is consumed by [`jar-loader.jar`](https://github.com/fidusio/jar-loader).

## What you get

- `target/[projectname]-fat.jar`
  - Your compiled classes at the root
  - Every runtime dependency (transitive too) as a nested `.jar` under `/lib`
  - Manifest with `Main-Class` pointing at your bootstrap

Different from `maven-shade-plugin`: nothing is merged or relocated. The launcher (`jar-loader`) is responsible for opening `/lib/*.jar` at runtime.

---

## 1. Parent POM (aggregator)

In the project root `pom.xml`, declare the assembly module:

```xml
<modelVersion>4.0.0</modelVersion>
<groupId>com.example</groupId>
<artifactId>[projectname]</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>

<modules>
    <module>[projectname]-assembly</module>
    <!-- add more variants here (e.g. -25-assembly, -iot-assembly) -->
</modules>
```

Add more sibling `*-assembly` modules later if you need build variants (different JDKs, different platform deps, server vs IoT, etc.). One module per variant — same pattern, different deps/`finalName`/`mainClass`.

---

## 2. Module `pom.xml`

Path: `[projectname]-assembly/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <artifactId>[projectname]</artifactId>
        <groupId>com.example</groupId>
        <version>1.0.0</version>
        <relativePath/>
    </parent>

    <artifactId>[projectname]-assembly</artifactId>

    <dependencies>
        <!-- Put every runtime dep your Main needs here.
             They will all be packaged into /lib of the fat jar. -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>[projectname]-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- ...more deps... -->
    </dependencies>

    <build>
        <finalName>[projectname]-fat</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>${maven-assembly-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                        <configuration>
                            <descriptors>
                                <descriptor>assembly.xml</descriptor>
                            </descriptors>
                            <archive>
                                <manifest>
                                    <mainClass>com.example.app.Main</mainClass>
                                </manifest>
                            </archive>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Variables to replace:**
- `[projectname]` — your project artifact id
- `com.example` — your groupId
- `com.example.app.Main` — your bootstrap class
- `${maven-assembly-plugin.version}` — pin in the parent POM (e.g. `3.7.1`)

---

## 3. Assembly descriptor

Path: `[projectname]-assembly/assembly.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
                              https://maven.apache.org/xsd/assembly-2.2.0.xsd">
    <id>jar-with-dependencies</id>
    <formats>
        <format>jar</format>
    </formats>
    <baseDirectory>/</baseDirectory>

    <!-- Your module's own compiled classes at the jar root -->
    <fileSets>
        <fileSet>
            <directory>${project.build.outputDirectory}</directory>
            <outputDirectory>/</outputDirectory>
            <includes>
                <include>**/*</include>
            </includes>
        </fileSet>
    </fileSets>

    <!-- All dependency jars under /lib, NOT unpacked -->
    <dependencySets>
        <dependencySet>
            <outputDirectory>/lib</outputDirectory>
            <useTransitiveDependencies>true</useTransitiveDependencies>
            <useProjectArtifact>true</useProjectArtifact>
            <unpack>false</unpack>
        </dependencySet>
    </dependencySets>
</assembly>
```

Do not change the layout (`/` for classes, `/lib` for jars) — `jar-loader` expects exactly this.

---

## 4. Bootstrap `Main` class

Path: `[projectname]-assembly/src/main/java/com/example/app/Main.java`

Keep it thin: parse args, load JSON configs, start services. Two arg-parsing styles — pick one.

### Style A — `key=value` with enum dispatch

```java
package com.example.app;

public class Main {
    public enum Param implements SetNameValue<Object> {
        WS("WebServer"),
        DS_CONFIG("DataStore");
        // ...name, getValue, setValue boilerplate
    }

    public static void main(String... args) {
        try {
            long ts = System.currentTimeMillis();
            List<GetNameValue<String>> parameters = SharedStringUtil.parseStrings('=', args);

            for (GetNameValue<String> gnv : parameters) {
                Param p = SharedUtil.lookupEnum(gnv.getName(), Param.values());
                if (p == null) continue;
                switch (p) {
                    case WS:
                        File f = IOUtil.locateFile(gnv.getValue());
                        HTTPServerConfig hsc = GSONUtil.fromJSON(
                            IOUtil.inputStreamToString(f), HTTPServerConfig.class);
                        new NIOHTTPServerCreator().setAppConfig(hsc).createApp();
                        p.setValue(hsc);
                        break;
                    // ...other cases...
                }
            }

            // require at least one config to be active
            // log startup duration
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("usage: WebServer=ws.json DataStore=ds.json ...");
            System.exit(-1);
        }
    }
}
```

Invocation: `java -jar [projectname]-fat.jar WebServer=ws.json DataStore=ds.json`

### Style B — `-flag value` with `ParamUtil`

```java
public static void main(String... args) {
    ParamUtil.ParamMap params = ParamUtil.parse("-", args);
    String wsConfig   = params.stringValue("-wsc", true);
    String flowConfig = params.stringValue("-fc",  true);

    if (wsConfig != null) { /* start HTTP */ }
    if (flowConfig != null) { /* start flow */ }
    if (wsConfig == null && flowConfig == null)
        throw new IllegalArgumentException("No config found");
}
```

Invocation: `java -jar [projectname]-fat.jar -wsc ws.json -fc flow.json`

### Why this shape

- **One binary, many deployments.** Each config block is optional. Same fat jar can be "API-only", "worker-only", or "everything" depending on which configs you pass.
- **No hardcoded wiring.** Behavior is determined entirely by JSON configs at startup.
- **Linear and obvious.** No DI container; adding a service = new `case` + new DAO type.
- **Always log startup time** (`Const.TimeInMillis.toString(System.currentTimeMillis() - ts)`) — cheap regression signal.

---

## 5. Build

From the project root:

```bash
mvn clean package
```

Output: `[projectname]-assembly/target/[projectname]-fat.jar`

---

## 6. Run

```bash
java -jar [projectname]-fat.jar <args>
```

If you use `jar-loader`, the loader handles classpath; otherwise, ensure the manifest's `Main-Class` and the `/lib` nested jars are resolvable by your launcher.

---

## 7. Adding build variants later

To add a JDK-25 build, a 64-bit native build, etc., copy the assembly module to a new sibling:

```
[projectname]-25-assembly/
[projectname]-iot-64-assembly/
```

For each new variant, override only what differs in its `pom.xml`:
- `<artifactId>` and `<finalName>` (so jars don't collide)
- `<mainClass>` if the entry point differs
- `<maven.compiler.source>` / `target` for a different JDK level
- swap platform-specific deps (e.g. `*-gpio-32` → `*-gpio-64`)

Everything else (the `assembly.xml`, the build plugin block) stays identical.

---

## Checklist

- [ ] Parent `pom.xml` lists the assembly module under `<modules>`
- [ ] Module `pom.xml` sets `finalName`, declares all runtime deps, configures `maven-assembly-plugin` with `mainClass`
- [ ] `assembly.xml` puts classes at `/` and deps at `/lib` with `<unpack>false</unpack>`
- [ ] `Main.java` is config-driven, optional services, logs startup time
- [ ] `mvn clean package` produces `target/[projectname]-fat.jar`
