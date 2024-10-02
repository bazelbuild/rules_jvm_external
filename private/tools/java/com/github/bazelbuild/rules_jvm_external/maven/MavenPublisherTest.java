package com.github.bazelbuild.rules_jvm_external.maven;

import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.junit.Test;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MavenPublisherTest {

    @Test
    public void testDeserialization() throws Exception {
        String xml = "<metadata modelVersion=\"1.1.0\">\n" +
                "    <groupId>com.mycompany.app</groupId>\n" +
                "    <artifactId>my-app</artifactId>\n" +
                "    <versioning>\n" +
                "        <latest>1.0</latest>\n" +
                "        <release>1.0</release>\n" +
                "        <versions>\n" +
                "            <version>1.0</version>\n" +
                "        </versions>\n" +
                "        <lastUpdated>20200731090423</lastUpdated>\n" +
                "    </versioning>\n" +
                "</metadata>";
        Metadata metadata =  new MetadataXpp3Reader().read(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), false);

        assertEquals(metadata.getGroupId(), "com.mycompany.app");
        assertEquals(metadata.getArtifactId(), "my-app");

        Versioning versioning = metadata.getVersioning();
        assertEquals(versioning.getLatest(), "1.0");
        assertEquals(versioning.getLastUpdated(), "20200731090423");
    }

    @Test
    public void testSerialization() throws Exception {
        String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<metadata>\n" +
                "  <groupId>com.mycompany.app</groupId>\n" +
                "  <artifactId>my-app</artifactId>\n" +
                "  <versioning>\n" +
                "    <latest>1.0</latest>\n" +
                "    <release>1.0</release>\n" +
                "    <versions>\n" +
                "      <version>1.0</version>\n" +
                "    </versions>\n" +
                "    <lastUpdated>20200731090423</lastUpdated>\n" +
                "  </versioning>\n" +
                "</metadata>\n";
        Metadata metadata = new Metadata();
        metadata.setGroupId("com.mycompany.app");
        metadata.setArtifactId("my-app");
        Versioning versioning = new Versioning();
        versioning.setLatest("1.0");
        versioning.setRelease("1.0");
        versioning.setLastUpdated("20200731090423");
        versioning.addVersion("1.0");
        metadata.setVersioning(versioning);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        new MetadataXpp3Writer().write(os, metadata);
        String actual = new String(os.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(actual, expected);
    }


}
