package me.nikitwentytwo.maven.jooqcodegen;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import static org.apache.maven.plugins.annotations.ResolutionScope.TEST;

/**
 * Declare this Mojo only for the XML schema that will help others understand how to configure it.
 */
@Mojo(
    name = "jooq-codegen-escaper",
    defaultPhase = GENERATE_SOURCES,
    requiresDependencyResolution = TEST,
    threadSafe = true
)
public class JooqCodegenMojo extends AbstractMojo {
    @Parameter(required = true)
    private List<String> inputFiles;

    @Parameter(property = "maven.jooqcodegen.force")
    private boolean force;

    @Override
    public void execute() { }
}
