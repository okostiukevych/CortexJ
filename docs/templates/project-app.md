# Project template — AI-enabled JVM application

Copy this structure for a production JVM app adding AI capability.

```text
app/
├── pom.xml              # imports cortexj-bom, uses one starter
└── src/main/java/
    └── com/example/
        └── Service.java # business code; no engine types
```

`pom.xml` skeleton:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>my-app</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.cortexj</groupId>
        <artifactId>cortexj-bom</artifactId>
        <version>0.1.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.cortexj</groupId>
      <artifactId>cortexj-starter-java17</artifactId>
    </dependency>
  </dependencies>
</project>
```

First use:

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("company/model"));
```

The platform answers: source, revision, artifacts, runtime, device,
memory, conversion, fallback, reasoning. Business code stays clean of
engine types unless you opt into the expert path.
