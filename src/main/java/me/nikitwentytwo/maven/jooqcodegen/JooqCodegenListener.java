package me.nikitwentytwo.maven.jooqcodegen;

import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Adler32;

@Component(role = MojoExecutionListener.class)
public class JooqCodegenListener implements MojoExecutionListener {
    static Logger log = LoggerFactory.getLogger(JooqCodegenListener.class);

    static final String DEFAULT_TARGET_DIRECTORY = "target/generated-sources/jooq";
    static final String CHECKSUM_FILENAME = ".jooq-checksum";

    private ConcurrentHashMap<MavenProject, ListenerExecution> listenerExecutionCache = new ConcurrentHashMap<>();

    private static class ListenerExecution {
        final String checksum;
        final boolean skipped;
        final List<Path> inputFilePaths;
        final Path targetPath;

        ListenerExecution(String checksum, boolean skipped, List<Path> inputFilePaths, Path targetPath) {
            this.checksum = checksum;
            this.skipped = skipped;
            this.inputFilePaths = inputFilePaths;
            this.targetPath = targetPath;
        }
    }

    @Override
    public void beforeMojoExecution(MojoExecutionEvent mojoExecutionEvent) {
        boolean isThisJooqCodegenPlugin = mojoExecutionEvent.getMojo().getClass()
                .getName().equals("org.jooq.codegen.maven.Plugin");
        if (!isThisJooqCodegenPlugin) {
            return;
        }

        Field skipJooqField;
        try {
            skipJooqField = mojoExecutionEvent.getMojo().getClass().getDeclaredField("skip");
            skipJooqField.setAccessible(true);
            boolean skipIsAlreadySet = (boolean) skipJooqField.get(mojoExecutionEvent.getMojo());

            if (skipIsAlreadySet) {
                return;
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Couldn't read jOOQ skip flag.");
            return;
        }

        List<String> inputFiles = getInputFilesConfiguration(mojoExecutionEvent.getProject().getModel());

        if (inputFiles.isEmpty()) {
            log.info("Couldn't find configured inputFiles for jooq-codegen-maven-incremental plugin, skipping it. " +
                    "(jOOQ will run as usual)");
            return;
        }

        Path baseDir = mojoExecutionEvent.getProject().getBasedir().toPath();
        Path targetPath = baseDir.resolve(Path.of(getTargetDirectory(mojoExecutionEvent.getExecution().getConfiguration())));
        List<Path> inputFilePaths = getInputFilePaths(baseDir, inputFiles, fileExpressionEvaluator(mojoExecutionEvent));

        log.debug("Using the following input files to determine staleness of jOOQ classes: {}", inputFilePaths);

        String checksum = checksumInputs(
                inputFilePaths,
                baseDir,
                mojoExecutionEvent.getExecution().getConfiguration().toString().hashCode());

        if (!isJooqGenerationForced(mojoExecutionEvent) &&
            !shouldRegenerateAccordingToInputChecksum(targetPath, checksum)) {

            try {
                skipJooqField.set(mojoExecutionEvent.getMojo(), true);
                listenerExecutionCache.put(
                        mojoExecutionEvent.getProject(),
                        new ListenerExecution(checksum, true, inputFilePaths, baseDir.resolve(targetPath)));
                return;
            } catch (IllegalAccessException e) {
                log.warn("Couldn't force jOOQ skip flag to true.");
            }
        }

        listenerExecutionCache.put(
                mojoExecutionEvent.getProject(),
                new ListenerExecution(checksum, false, inputFilePaths, targetPath));
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent mojoExecutionEvent) {
        boolean isThisJooqCodegenPlugin = mojoExecutionEvent.getMojo().getClass()
                .getName().equals("org.jooq.codegen.maven.Plugin");
        if (!isThisJooqCodegenPlugin) {
            return;
        }

        ListenerExecution beforeExecution = listenerExecutionCache.get(mojoExecutionEvent.getProject());

        if (beforeExecution == null || beforeExecution.inputFilePaths.isEmpty() || beforeExecution.skipped) {
            return;
        }

        Path baseDir = mojoExecutionEvent.getProject().getBasedir().toPath();

        String checksum = checksumInputs(
                beforeExecution.inputFilePaths,
                baseDir,
                mojoExecutionEvent.getExecution().getConfiguration().toString().hashCode());

        List<String> targetFiles;
        try (Stream<Path> files = Files.walk(beforeExecution.targetPath, 16)) {
            targetFiles = files
                    .filter(p -> p.toFile().isFile())
                    .map(beforeExecution.targetPath::relativize)
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Couldn't list all files in the " + beforeExecution.targetPath + " in order to write a checksum file.");
            return;
        }

        byte[] lineSeparator = System.lineSeparator().getBytes();
        try (FileOutputStream os = new FileOutputStream(beforeExecution.targetPath.resolve(CHECKSUM_FILENAME).toFile(), false)) {
            os.write(checksum.getBytes());
            os.write(lineSeparator);

            for (String generatedFile : targetFiles) {
                os.write(generatedFile.getBytes());
                os.write(lineSeparator);
            }
        } catch (IOException e) {
            log.warn("Couldn't write to the .jooq-checksum file", e);
            return;
        }

        log.info("Saved checksum file for jOOQ classes, next time jOOQ generation will be " +
                "skipped unless inputs change.");
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent mojoExecutionEvent) {
        File checksumFile = Path.of(getTargetDirectory(mojoExecutionEvent.getExecution().getConfiguration()))
                .resolve(CHECKSUM_FILENAME)
                .toFile();

        if (checksumFile.exists()) {
            checksumFile.delete();
        }
    }

    /**
     * Finds out the &lt;inputFiles&gt; configuration that was specified against {@link JooqCodegenMojo}
     */
    private List<String> getInputFilesConfiguration(Model projectModel) {
        // It's hacky, but it's the only way that I found how one can get Mojo config in the execution listener.
        return Optional.ofNullable(projectModel
                        .getBuild()
                        .getPluginsAsMap()
                        .get("me.nikitwentytwo:jooq-codegen-maven-incremental"))
                .filter(p -> p.getConfiguration() != null && p.getConfiguration() instanceof Xpp3Dom)
                .map(p -> (Xpp3Dom) p.getConfiguration())
                .map(c -> c.getChild("inputFiles"))
                .map(Xpp3Dom::getChildren)
                .map(filesAsDomElements -> Arrays.stream(filesAsDomElements)
                        .map(Xpp3Dom::getValue)
                        .collect(Collectors.toList()))
                .orElse(new ArrayList<>());
    }

    /**
     * It's possible to force the regeneration of jOOQ classes by using -Dmaven.jooqcodegen.force=true property,
     *   or by putting the {@code <force>true</force>} configuration in pom.xml
     */
    private boolean isJooqGenerationForced(MojoExecutionEvent event) {
        PluginParameterExpressionEvaluator evaluator =
                new PluginParameterExpressionEvaluator(event.getSession(), event.getExecution());

        String forcedByProperty = System.getProperty("maven.jooqcodegen.force");
        if (forcedByProperty != null) {
            try {
                return (boolean) evaluator.evaluate(forcedByProperty, Boolean.class);
            } catch (ExpressionEvaluationException e) {
                log.warn("Couldn't parse system property maven.jooqcodegen.force with value: {}, expecting boolean", forcedByProperty);
                return true;
            }
        }

        return Optional.ofNullable(event.getProject().getModel()
                        .getBuild()
                        .getPluginsAsMap()
                        .get("me.nikitwentytwo:jooq-codegen-maven-incremental"))
                .filter(p -> p.getConfiguration() != null && p.getConfiguration() instanceof Xpp3Dom)
                .map(p -> (Xpp3Dom) p.getConfiguration())
                .map(c -> c.getChild("force"))
                .map(Xpp3Dom::getValue)
                .map(v -> {
                    try {
                        return (boolean) evaluator.evaluate(v, Boolean.class);
                    } catch (ExpressionEvaluationException e) {
                        log.warn("Couldn't parse configuration value in the '<force>' tag: {}, expecting boolean", v);
                        return true;
                    }
                })
                .orElse(false);
    }

    /**
     * Given the {@code <inputFiles>} configuration that contains AntPath patterns, find all the files
     * that match the patterns.
     */
    private List<Path> getInputFilePaths(Path baseDir, List<String> inputFiles, Function<String, String> expressionEvaluator) {
        return inputFiles.stream()
                .map(expressionEvaluator)
                .map(this::findAllFiles)
                .flatMap(List::stream)
                .map(baseDir::relativize)
                .collect(Collectors.toList());
    }

    /**
     * Returns the target directory where jOOQ codegen was configured to dump the generated class sources.
     * @param configuration Object that represents parsed configuration of the jooq-codegen-maven plugin.
     */
    private String getTargetDirectory(Xpp3Dom configuration) {
        return Optional.of(configuration)
                .map(c -> c.getChild("generator"))
                .map(c -> c.getChild("target"))
                .map(c -> c.getChild("directory"))
                .map(Xpp3Dom::getValue)
                .orElse(DEFAULT_TARGET_DIRECTORY);
    }

    /**
     * Returns a Function that evaluates configuration values that are supposed to be the paths, it will
     * substitute variables like project.basedir, etc. and will make paths absolute as well.
     */
    private Function<String, String> fileExpressionEvaluator(MojoExecutionEvent event) {
        PluginParameterExpressionEvaluator expressionEvaluator =
                new PluginParameterExpressionEvaluator(event.getSession(), event.getExecution());

        return fileExpressionPattern -> {
            try {
                return expressionEvaluator.alignToBaseDirectory(
                                new File((String) expressionEvaluator.evaluate(fileExpressionPattern, String.class)))
                        .getPath();
            } catch (ExpressionEvaluationException e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Evaluates an AntPath-like pattern.
     * Example: /full/path/to/directory/**.sql or /full/path/to/directory/*.sql
     */
    private List<Path> findAllFiles(String path) {
        String baseDir;
        String fileNamePattern;

        int asteriskPos = path.indexOf('*');
        Path wholePath = Path.of(path);
        if (asteriskPos != -1) {
            int slashPos = path.lastIndexOf("/", asteriskPos);
            if (slashPos != -1) {
                baseDir = path.substring(0, slashPos);
                fileNamePattern = path.substring(slashPos + 1);
            } else {
                return listOf(wholePath);
            }
        } else {
            return listOf(wholePath);
        }

        fileNamePattern = fileNamePattern.replace(".", "\\.");
        fileNamePattern = fileNamePattern.replace("**", ".+");
        fileNamePattern = fileNamePattern.replace("*", "[^/]+");
        Predicate<String> compiledPatternPredicate = Pattern.compile(fileNamePattern).asMatchPredicate();
        Path basePath = Path.of(baseDir);

        try (Stream<Path> files = Files.walk(basePath, 16)) {
            return files
                    .filter(p -> p.toFile().isFile())
                    .filter(p -> compiledPatternPredicate.test(basePath.relativize(p).toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Couldn't list files in {}", baseDir);
            return new ArrayList<>();
        }
    }

    @SafeVarargs
    private static <T> List<T> listOf(T ...elements) {
        List<T> l = new ArrayList<>(elements.length);
        Collections.addAll(l, elements);
        return l;
    }

    private String checksumInputs(List<Path> paths, Path projectBaseDir, int checksumOfConfiguration) {
        Adler32 checksum = new Adler32();
        checksum.update(checksumOfConfiguration);
        for (Path inputFile : paths) {
            checksum.update(inputFile.toString().getBytes());

            try {
                checksum.update(Files.readAllBytes(projectBaseDir.resolve(inputFile)));
            } catch (IOException e) {
                throw new RuntimeException("Couldn't read " + inputFile
                        + " while calculating the checksum.", e);
            }
        }

        return Long.toString(checksum.getValue());
    }

    /**
     * Validate all files in the schemaDefinitionInputs directories, by constructing a checksum
     * of these files and verifying that the target directory already contains that checksum.
     * If the target directory doesn't contain the checksum, or checksums don't match, then
     * we need to regenerate Jooq classes.
     */
    private boolean shouldRegenerateAccordingToInputChecksum(
            Path targetDirectory,
            String calculatedInputChecksum) {

        // Checksum file contains:
        // 1. first line is a checksum hash, which is calculated based on the current Jooq
        //     configuration and on the checksum of all input files
        // 2. subsequent lines are filenames

        File checksumFile = targetDirectory.resolve(CHECKSUM_FILENAME).toFile();
        if (!checksumFile.exists() || !checksumFile.isFile()) {
            log.info("Checksum file doesn't exist, will regenerate Jooq classes.");
            return true;
        }

        String savedChecksum;
        Set<String> generatedFiles = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(checksumFile))) {
            String checksumLine = reader.readLine();
            if (checksumLine == null) {
                log.info("The checksum file is empty, will regenerate Jooq classes.");
                return true;
            }

            savedChecksum = checksumLine.trim();
            reader.lines().filter(s -> !s.isEmpty()).forEach(generatedFiles::add);
        } catch (IOException e) {
            log.warn("Couldn't read checksum file. If you're unsure " +
                    "how to solve this issue, try removing the " + CHECKSUM_FILENAME + " file", e);
            return true;
        }

        if (!savedChecksum.equals(calculatedInputChecksum)) {
            log.info("Checksums didn't match, will regenerate Jooq classes.");
            return true;
        }

        // once we see that checksums match, we just want to ensure that the files are still actually there

        for (String generatedFile : generatedFiles) {
            if (Files.notExists(targetDirectory.resolve(generatedFile))) {
                log.info("Detected that a generated file " + generatedFile
                        + " doesn't exist anymore, will regenerate Jooq classes.");
                return true;
            }
        }

        log.info("Checksums matched, all generated files exist, will skip " +
                "regeneration of Jooq classes.");

        return false;
    }
}
