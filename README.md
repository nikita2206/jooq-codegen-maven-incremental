# Maven jOOQ incremental codegen extension

This Maven extension adds incremental codegen to projects using jOOQ.

It is useful for projects that use jOOQ and Testcontainers to generate jOOQ classes,
  which normally involves spinning up a Docker container, applying all migrations and then generating the classes,
  on e.g. every `mvn compile` phase. The container startup, migrations and codegen can take quite some time which
  negates Maven's support of incremental compilation.

### How to use it

```xml
<!-- Next to your jOOQ codegen plugin declaration add the following -->
<plugins>
  <plugin>
    <groupId>me.nikitwentytwo</groupId>
    <artifactId>jooq-codegen-maven-incremental</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <extensions>true</extensions> <!-- Pay attention to this property, it has to be set to true -->
    <configuration>
      <inputFiles>
        <!-- The following takes ant-path like patterns -->
        <inputFile>${project.basedir}/src/main/resources/db/migration/*.sql</inputFile>
        <inputFile>${project.basedir}/../../database/init.sql</inputFile>
      </inputFiles>
    </configuration>
  </plugin>
  <plugin>
      <groupId>org.jooq</groupId>
      <artifactId>jooq-codegen-maven</artifactId>
      <configuration>...</configuration>
  </plugin>
</plugins>
```
The above ensures that the jOOQ codegen plugin will only run if one of the input files have changed
after the last time jOOQ classes were generated.

### Configuration

<table>
<thead>
<tr>
<th>XML</th>
<th>Java property</th>
<th>Default</th>
</tr>
</thead>
<tbody>
  <tr>
    <td><code>&lt;inputFiles&gt;</code></td>
    <td>-</td>
    <td>-</td>
  </tr>
  <tr>
    <td colspan="3">Input files, if they don't change jOOQ generation will be skipped.</td>
  </tr>

  <tr>
    <td><code>&lt;force&gt;</code></td>
    <td>-Dmaven.jooqcodegen.force</td>
    <td>false</td>
  </tr>
  <tr>
    <td colspan="3">Force regeneration of jOOQ sources regardless of input files changes.</td>
  </tr>
</tbody>
</table>


### How it works

After the jOOQ codegen plugin generates the classes, this extension will calculate a checksum of all the input files
 and will save it in a file named `.jooq-checksum`, along the generated classes. (usually in the `target/generated-sources/jooq` directory)  
Next time the jOOQ codegen is triggered, this extension will first check if there is a `.jooq-checksum` file,
 if found then it will compare the checksum stored in that file to the checksum of all files found in the declared `<inputFiles>` patterns,
 if the checksums match, then the jOOQ codegen plugin will be skipped.
